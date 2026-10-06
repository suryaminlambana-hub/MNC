package com.mnc.ausy.service;

import com.mnc.ausy.domain.GroupUser;
import com.mnc.ausy.domain.TlogActivity;
import com.mnc.ausy.domain.TlogTransaction;
import com.mnc.ausy.domain.User;
import com.mnc.ausy.repo.GroupUserRepository;
import com.mnc.ausy.repo.TlogActivityRepository;
import com.mnc.ausy.repo.TlogTransactionRepository;
import com.mnc.ausy.repo.UserRepository;
import com.mnc.ausy.security.AusyUserDetails;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Siapa saja yang jejaknya boleh dilihat seseorang di audit trail (BUG_AUSY_00115).
 *
 * Pemisahannya tegas: yang bekerja di sisi operasional hanya melihat jejak Maker &amp; Checker,
 * yang di sisi administratif hanya melihat jejak Admin &amp; Admin Approval. Sisi sebuah group
 * dibaca dari menu yang dipegangnya ({@link AccessService#sideOf}), jadi group buatan admin ikut
 * tersaring tanpa perlu menulis id group di kode.
 *
 * Dipakai bersama oleh halaman Audit Trail, export-nya, dan card "Aktivitas Terbaru" di
 * dashboard — supaya tidak ada satu pun jalan yang menampilkan log gabungan.
 */
@Service
public class AuditScopeService {

    private final GroupUserRepository groups;
    private final AccessService access;
    private final ApprovalService approval;
    private final UserRepository users;
    private final PairingService pairing;
    private final TlogTransactionRepository tx;
    private final TlogActivityRepository act;

    public AuditScopeService(GroupUserRepository groups, AccessService access, ApprovalService approval,
                             UserRepository users, PairingService pairing,
                             TlogTransactionRepository tx, TlogActivityRepository act) {
        this.groups = groups; this.access = access; this.approval = approval;
        this.users = users; this.pairing = pairing; this.tx = tx; this.act = act;
    }

    public static final String BUSINESS = "BUSINESS";   // jejak Maker & Checker
    public static final String CONFIG    = "CONFIG";    // jejak Admin & Admin Approval

    /**
     * Group yang termasuk sisi tertentu. Sisinya ditentukan oleh halaman yang dibuka
     * (Audit Trail operasional vs administratif), jadi isinya pasti — tidak lagi ditebak dari
     * menu lain yang kebetulan dipegang si pembaca.
     */
    public List<Integer> groupsOnSide(String side) {
        List<Integer> out = new ArrayList<>();
        for (GroupUser g : groups.findAll())
            if (side != null && side.equals(access.sideOf(g.getIdGroup()))) out.add(g.getIdGroup());
        if (out.isEmpty()) out.add(-1);          // jangan pernah jatuh ke "tanpa filter"
        return out;
    }

    /**
     * Jejak yang boleh dilihat SEORANG penyetuju: group-nya sendiri + group yang memang
     * diotorisasinya. Checker tidak lagi melihat aktivitas maker di luar naungannya
     * (BUG_AUSY_00149); yang menentukan tetap matriks Approval Group, bukan "satu sisi".
     */
    public List<Integer> groupsVisibleTo(Integer viewerGroup, String side) {
        List<Integer> out = new ArrayList<>();
        if (viewerGroup == null) { out.add(-1); return out; }
        out.add(viewerGroup);
        for (Integer g : groupsOnSide(side)) {
            if (g == null || g.equals(viewerGroup) || out.contains(g)) continue;
            boolean mine = CONFIG.equals(side)
                    ? approval.canApproveConfigGroup(viewerGroup, g)
                    : approval.canApproveGroup(viewerGroup, g);
            if (mine) out.add(g);
        }
        return out;
    }

    // ------------------------------------------------- jejak sisi config, per user

    /**
     * Pengawas tertinggi sisi config: Akun Utama bawaan sistem YANG SEKALIGUS memegang role
     * penyetuju config — hari ini persis {@code mncsuperadmin} (REQ_AUSY_00007).
     *
     * <p>Dikenali dari sifatnya, bukan dari user_id yang ditulis di kode: nama akun bisa
     * berganti, dan aturan yang dipatok ke satu nama akan diam-diam putus kalau itu terjadi.
     * Admin Approval yang dibuat belakangan BUKAN Akun Utama, jadi ia tetap dibatasi pasangannya
     * sendiri — sesuai REQ_AUSY_00008.
     */
    public boolean pengawasConfig(User u) {
        return u != null && u.isProtectedAccount() && approval.configAuthorizer(u.getIdGroup());
    }

    private User userOf(AusyUserDetails me) {
        return me == null ? null : users.findByUserId(me.getUsername()).orElse(null);
    }

    /**
     * Jejak transaksi sisi config yang boleh dilihat {@code me} (REQ_AUSY_00007 &amp; 00008).
     *
     * Tiga pandangan yang berbeda tajam:
     * <ul>
     *   <li><b>Pengawas</b> — seluruh jejak Admin &amp; Admin Approval, tanpa kecuali.</li>
     *   <li><b>Admin Approval biasa</b> — jejaknya sendiri + jejak admin yang dipasangkan padanya.</li>
     *   <li><b>Admin</b> — jejaknya sendiri + keputusan approver ATAS PENGAJUANNYA. Bukan seluruh
     *       log pasangannya: satu Admin Approval memegang banyak admin, jadi menampilkan lognya
     *       utuh justru membocorkan pengajuan admin lain.</li>
     * </ul>
     */
    public List<TlogTransaction> configTransactions(AusyUserDetails me, int limit) {
        User u = userOf(me);
        if (pengawasConfig(u)) return tx.findByUserGroups(groupsOnSide(CONFIG), PageRequest.of(0, limit));
        if (u == null) return List.of();
        if (pairing.isConfigApprover(u))
            return tx.findByUserIds(sendiriDanPasangan(u), PageRequest.of(0, limit));
        if (pairing.isConfigMaker(u))
            return tx.configVisibleToAdmin(u.getUserId(), "%(maker=" + u.getUserId() + ")%",
                    PageRequest.of(0, limit));
        return tx.findByUserGroups(groupsVisibleTo(u.getIdGroup(), CONFIG), PageRequest.of(0, limit));
    }

    /**
     * Jejak login/aktivitas sisi config yang boleh dilihat {@code me}.
     *
     * Untuk Admin isinya hanya miliknya sendiri: login seorang approver bukan "otorisasi atas
     * pengajuan saya", jadi tidak ada alasan ia terlihat.
     */
    public List<TlogActivity> configActivities(AusyUserDetails me, int limit) {
        User u = userOf(me);
        if (pengawasConfig(u)) return act.findByUserGroups(groupsOnSide(CONFIG), PageRequest.of(0, limit));
        if (u == null) return List.of();
        if (pairing.isConfigApprover(u))
            return act.findByUserIds(sendiriDanPasangan(u), PageRequest.of(0, limit));
        if (pairing.isConfigMaker(u))
            return act.findByUserIds(Set.of(u.getUserId()), PageRequest.of(0, limit));
        return act.findByUserGroups(groupsVisibleTo(u.getIdGroup(), CONFIG), PageRequest.of(0, limit));
    }

    /** Dirinya sendiri + admin yang dipasangkan padanya. */
    private Set<String> sendiriDanPasangan(User u) {
        Set<String> out = new LinkedHashSet<>();
        out.add(u.getUserId());
        out.addAll(pairing.makersOf(u.getUserId()));
        return out;
    }

    /** Apakah pandangan user ini dibatasi? Dipakai untuk menerangkannya di layar. */
    public boolean configDibatasi(AusyUserDetails me) {
        return !pengawasConfig(userOf(me));
    }

    /** Label sisi untuk ditampilkan di halaman audit. */
    public String label(String side) {
        return CONFIG.equals(side) ? "Admin & Admin Approval" : "Maker & Checker";
    }
}
