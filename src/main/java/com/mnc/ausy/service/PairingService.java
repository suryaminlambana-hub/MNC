package com.mnc.ausy.service;

import com.mnc.ausy.domain.CheckerMaker;
import com.mnc.ausy.domain.GroupUser;
import com.mnc.ausy.domain.User;
import com.mnc.ausy.repo.CheckerMakerRepository;
import com.mnc.ausy.repo.GroupUserRepository;
import com.mnc.ausy.repo.UserRepository;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * Pasangan otorisasi per USER: checker mana berwenang atas maker mana (BUG_AUSY_00139).
 *
 * Matriks {@code approval_group} tetap menentukan ROLE mana boleh menyetujui role mana — itu yang
 * menjaga segregation of duties. Tabel {@code checker_maker} mempersempitnya satu tingkat lagi:
 * dari semua maker yang secara role boleh diotorisasi seorang checker, hanya maker yang benar-benar
 * dipasangkan padanya yang terlihat dan bisa diputuskan olehnya.
 *
 * Checker yang belum dipasangkan ke siapa pun antriannya kosong — disengaja, supaya pemasangan
 * tidak pernah terlewat diam-diam.
 */
@Service
public class PairingService {

    private final CheckerMakerRepository pairs;
    private final UserRepository users;
    private final GroupUserRepository groups;
    private final AccessService access;
    private final ApprovalService approval;

    public PairingService(CheckerMakerRepository pairs, UserRepository users, GroupUserRepository groups,
                          AccessService access, ApprovalService approval) {
        this.pairs = pairs; this.users = users; this.groups = groups;
        this.access = access; this.approval = approval;
    }

    // ---------------------------------------------------------------- pembacaan

    /** Maker yang dipegang seorang checker. */
    public Set<String> makersOf(String checker) {
        Set<String> out = new LinkedHashSet<>();
        if (checker == null) return out;
        for (CheckerMaker p : pairs.findByChecker(checker)) out.add(p.getMaker());
        return out;
    }

    /** Checker yang memegang seorang maker. */
    public Set<String> checkersOf(String maker) {
        Set<String> out = new LinkedHashSet<>();
        if (maker == null) return out;
        for (CheckerMaker p : pairs.findByMaker(maker)) out.add(p.getChecker());
        return out;
    }

    public boolean pairedWith(String checker, String maker) {
        return checker != null && maker != null && pairs.existsByCheckerAndMaker(checker, maker);
    }

    /**
     * Role yang mengerjakan data GL dan diotorisasi role lain — kandidat "maker" di layar
     * pemasangan. Dibaca dari data (halaman pengajuan GL + matriks approval), bukan id role
     * yang ditulis di kode, supaya role bisnis buatan admin ikut terbawa.
     */
    public boolean isMakerGroup(Integer idGroup) {
        return idGroup != null && access.canSubmitGl(idGroup) && approval.requiresApproval(idGroup);
    }

    /** Role penyetuju untuk role maker mana pun — kandidat "checker" di layar pemasangan. */
    public boolean isCheckerGroup(Integer idGroup) {
        if (idGroup == null) return false;
        for (GroupUser g : groups.findAll())
            if (isMakerGroup(g.getIdGroup()) && approval.canApproveGroup(idGroup, g.getIdGroup())) return true;
        return false;
    }

    public boolean isMaker(User u) { return u != null && isMakerGroup(u.getIdGroup()); }
    public boolean isChecker(User u) { return u != null && isCheckerGroup(u.getIdGroup()); }

    // ------------------------------------------------- pasangan sisi config (REQ_AUSY_00006)

    /**
     * Role sisi administratif yang MENGAJUKAN perubahan config — "Admin".
     *
     * Dibaca dari hak aksesnya ({@link AccessService#sideOf} = CONFIG) dan dari kenyataan bahwa
     * ia bukan pemegang menu Otorisasi Config, bukan dari id role yang ditulis di kode — supaya
     * role administratif buatan admin ikut terbawa tanpa menyentuh kelas ini lagi.
     */
    public boolean isConfigMakerGroup(Integer idGroup) {
        return idGroup != null && "CONFIG".equals(access.sideOf(idGroup)) && !approval.configAuthorizer(idGroup);
    }

    /** Role yang MENGOTORISASI perubahan config — "Admin Approval". */
    public boolean isConfigApproverGroup(Integer idGroup) {
        return approval.configAuthorizer(idGroup);
    }

    public boolean isConfigMaker(User u)    { return u != null && isConfigMakerGroup(u.getIdGroup()); }
    public boolean isConfigApprover(User u) { return u != null && isConfigApproverGroup(u.getIdGroup()); }

    /** User aktif yang berperan sebagai Admin Approval, urut nama. */
    public List<User> configApproverUsers() { return activeUsersWhere(this::isConfigApprover); }

    /** User aktif yang berperan sebagai Admin, urut nama. */
    public List<User> configMakerUsers() { return activeUsersWhere(this::isConfigMaker); }

    /**
     * Admin yang belum dipegang Admin Approval mana pun.
     *
     * Berbeda dari sisi bisnis, pengajuannya TETAP terlihat — otorisasi config ditentukan
     * matriks approval, bukan pasangan ini. Yang hilang adalah jejak audit: tanpa pasangan,
     * admin itu hanya melihat lognya sendiri. Karena itu tetap perlu ditandai di layar.
     */
    public List<User> unpairedConfigMakers() {
        List<User> out = new ArrayList<>();
        for (User u : configMakerUsers()) if (!pairs.existsByMaker(u.getUserId())) out.add(u);
        return out;
    }

    /** User aktif yang berperan sebagai checker bisnis, urut nama. */
    public List<User> checkerUsers() { return activeUsersWhere(this::isChecker); }

    /** User aktif yang berperan sebagai maker bisnis, urut nama. */
    public List<User> makerUsers() { return activeUsersWhere(this::isMaker); }

    private List<User> activeUsersWhere(java.util.function.Predicate<User> keep) {
        List<User> out = new ArrayList<>();
        for (User u : users.findAll())
            if (u.getStatusAccount() != null && u.getStatusAccount() == 1 && keep.test(u)) out.add(u);
        out.sort(Comparator.comparing(u -> u.getUserId() == null ? "" : u.getUserId()));
        return out;
    }

    /** Maker yang belum dipegang checker mana pun — pengajuannya tidak akan pernah terlihat. */
    public List<User> unpairedMakers() {
        List<User> out = new ArrayList<>();
        for (User u : makerUsers()) if (!pairs.existsByMaker(u.getUserId())) out.add(u);
        return out;
    }

    // ---------------------------------------------------------------- penulisan

    /**
     * Pasang ulang seluruh maker milik seorang checker. Dipanggil dari penerapan permintaan
     * perubahan config (dual control), bukan langsung dari layar.
     */
    public void replaceMakers(String checker, Collection<String> makers) {
        if (checker == null) return;
        Set<String> want = new LinkedHashSet<>();
        if (makers != null) for (String m : makers) if (m != null && !m.isBlank()) want.add(m.trim());
        for (CheckerMaker p : pairs.findByChecker(checker))
            if (!want.contains(p.getMaker())) pairs.delete(p);
        for (String m : want)
            if (!pairs.existsByCheckerAndMaker(checker, m)) {
                CheckerMaker p = new CheckerMaker();
                p.setChecker(checker); p.setMaker(m);
                pairs.save(p);
            }
    }

    /** Bersihkan pasangan milik user yang dihapus, dari sisi mana pun dia terdaftar. */
    public void forget(String userId) {
        if (userId == null) return;
        pairs.deleteByChecker(userId);
        pairs.deleteByMaker(userId);
    }
}
