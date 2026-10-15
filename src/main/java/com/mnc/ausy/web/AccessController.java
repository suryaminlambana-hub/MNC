package com.mnc.ausy.web;

import com.mnc.ausy.domain.Menu;
import com.mnc.ausy.domain.User;
import com.mnc.ausy.domain.UserMenu;
import com.mnc.ausy.repo.*;
import com.mnc.ausy.security.AusyUserDetails;
import com.mnc.ausy.service.AccessService;
import com.mnc.ausy.service.ApprovalService;
import com.mnc.ausy.service.ConfigChangeService;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.*;

/**
 * Access User — hak akses diatur per USER (BUG_AUSY_00143).
 *
 * Sebelumnya layar ini memilih ROLE dan mengubah {@code group_menu}, sehingga semua user dengan
 * role yang sama selalu memegang menu yang persis sama. Sekarang yang dipilih adalah user, dan
 * yang disimpan adalah {@code user_menu} miliknya sendiri.
 *
 * Centangan yang ditampilkan dibatasi ke section milik role user tersebut: memilih user ber-role
 * Maker hanya memperlihatkan bagian Maker, sehingga layarnya tidak lagi menyodorkan hak yang
 * memang bukan urusan role itu.
 */
@Controller
@RequestMapping("/access")
public class AccessController {
    private final GroupUserRepository groups;
    private final UserRepository users;
    private final MenuRepository menus;
    private final UserMenuRepository userMenus;
    private final ConfigChangeService configChange;
    private final ApprovalService approval;
    private final AccessService access;

    public AccessController(GroupUserRepository groups, UserRepository users, MenuRepository menus,
                            UserMenuRepository userMenus, ConfigChangeService configChange,
                            ApprovalService approval, AccessService access) {
        this.groups = groups; this.users = users; this.menus = menus; this.userMenus = userMenus;
        this.configChange = configChange; this.approval = approval; this.access = access;
    }

    @GetMapping
    public String index(@RequestParam(required=false) String user, Model model,
                        @AuthenticationPrincipal AusyUserDetails me) {
        // Dipakai untuk menyaring CENTANGAN menu saja (BUG_AUSY_00136, di bawah), bukan untuk
        // menyaring daftar user-nya: sejak REQ_AUSY_00063 Admin boleh mengatur user ber-role
        // CONFIG APPROVER, yang tidak boleh berpindah tangan hanyalah menu Admin Approval.
        boolean iAmConfigApprover = approval.configAuthorizer(me == null ? null : me.getIdGroup());
        Map<Integer,String> roleName = new HashMap<>();
        for (var g : groups.findAllByOrderByNamaGroupAsc()) roleName.put(g.getIdGroup(), g.getNamaGroup());

        // REQ_AUSY_00018: user ber-role EXECUTE tidak ditampilkan di sini. Role itu hanya
        // memegang satu hal — Generate File ACCTXT — dan haknya datang dari cetakan rolenya,
        // bukan dari pengaturan per user. Menampilkannya hanya membuat daftar ini panjang
        // tanpa ada yang bisa diatur.
        //
        // User ber-role penyetuju config juga tidak ditampilkan (BUG_AUSY_00133). REQ_AUSY_00063
        // membuka halaman User untuk mereka — ubah dan hapus kini boleh — tetapi halaman ini
        // tetap tertutup, karena role itu hanya memegang menu section Admin Approval dan section
        // itu disembunyikan dari role di bawahnya (BUG_AUSY_00136). Ditampilkan pun yang didapat
        // Admin hanya daftar centangan kosong.
        List<User> selectable = users.findAll().stream()
                .filter(u -> iAmConfigApprover || !approval.configAuthorizer(u.getIdGroup()))
                .filter(u -> !access.isExecuteRole(u.getIdGroup()))
                .sorted(Comparator.comparing(u -> u.getUserId() == null ? "" : u.getUserId()))
                .toList();
        model.addAttribute("users", selectable);
        model.addAttribute("roleName", roleName);
        model.addAttribute("selectedUser", user);
        model.addAttribute("pendingConfig", configChange.pendingListByMakerGroup(me == null ? null : me.getIdGroup()).size());
        model.addAttribute("pendingIds", configChange.pendingIds("ACCESS"));

        User target = user == null ? null : users.findByUserId(user).orElse(null);
        // Diperiksa lagi di sini: ?user=... bisa diketik langsung di alamat (REQ_AUSY_00018).
        if (target != null && access.isExecuteRole(target.getIdGroup())) target = null;
        if (target != null) {
            Set<Integer> granted = new HashSet<>();
            for (UserMenu um : userMenus.findByUserId(target.getUserId())) granted.add(um.getIdMenu());

            // Hanya section yang relevan untuk role user ini — inti dari BUG_AUSY_00143.
            // Role yang belum memegang apa pun (role baru buatan admin) tetap ditampilkan penuh,
            // kalau tidak hak-nya tidak akan pernah bisa mulai diisi.
            Set<String> allowedSections = access.sectionsOf(target.getIdGroup());
            boolean allSections = allowedSections.isEmpty();
            model.addAttribute("allSections", allSections);
            // hanya item aktif — menu lama yang sudah dilebur tidak perlu muncul sebagai centangan.
            // Section Admin Approval hanya terlihat oleh Admin Approval sendiri (BUG_AUSY_00136).
            List<Menu> all = menus.findAllByOrderByParentAscUrutanAsc().stream()
                    .filter(m -> m.getActive() != null && m.getActive() == 1)
                    .filter(m -> m.getBundledWith() == null)   // aksi yang menempel ikut menunya
                    .filter(m -> iAmConfigApprover || !"ADMIN_APV".equals(m.getSection()))
                    .filter(m -> allSections
                              || m.getParent() != null && m.getParent() == 0
                              || allowedSections.contains(m.getSection() == null ? "" : m.getSection()))
                    .toList();
            model.addAttribute("sections", sections(all));
            model.addAttribute("granted", granted);
            model.addAttribute("targetUser", target);
            model.addAttribute("targetRole", roleName.get(target.getIdGroup()));
        }
        return "access/index";
    }

    /**
     * Tambahkan aksi yang menempel pada menu yang dicentang. Tanpa ini, user penyetuju baru bisa
     * membuka halaman otorisasi tapi tidak berwenang menyetujui apa pun, karena endpoint
     * approve/reject-nya ada di baris hak akses yang terpisah.
     */
    private List<Integer> withBundledActions(List<Integer> menuIds) {
        List<Integer> out = new ArrayList<>(menuIds == null ? List.of() : menuIds);
        for (Menu m : menus.findByBundledWithIsNotNull())
            if (out.contains(m.getBundledWith()) && !out.contains(m.getIdMenu())) out.add(m.getIdMenu());
        return out;
    }

    /** Satu kelompok centangan di dalam sebuah section, mis. "Data" atau "Card Dashboard". */
    public record AccessGroup(String title, String kind, List<Menu> items) {}
    /** Satu bagian per role di halaman Access User (BUG_AUSY_00115). */
    public record AccessSection(String label, List<AccessGroup> groups) {}

    /** Urutan & judul section — item tanpa section masuk "Lainnya" di paling bawah. */
    private static final LinkedHashMap<String,String> SECTION_LABELS = new LinkedHashMap<>();
    static {
        SECTION_LABELS.put("MAKER",     "Maker — Transaksi & Data GL");
        SECTION_LABELS.put("CHECKER",   "Checker — Otorisasi Operasional");
        SECTION_LABELS.put("ADMIN",     "Admin — Konfigurasi Sistem");
        SECTION_LABELS.put("ADMIN_APV", "Admin Approval — Otorisasi Konfigurasi");
        SECTION_LABELS.put("",          "Lainnya");
    }

    /**
     * Susun centangan per role (Maker / Checker / Admin / Admin Approval), lalu di dalamnya
     * dikelompokkan lagi per parent menu supaya tetap terbaca "Data", "GL", "Card Dashboard".
     */
    private List<AccessSection> sections(List<Menu> all) {
        Map<Integer,String> parentName = new HashMap<>();
        for (Menu m : menus.findAllByOrderByParentAscUrutanAsc())
            if (m.getParent() != null && m.getParent() == 0) parentName.put(m.getIdMenu(), m.getNamaMenu());

        // section -> judul kelompok -> item
        Map<String, LinkedHashMap<String, List<Menu>>> bySection = new LinkedHashMap<>();
        for (String key : SECTION_LABELS.keySet()) bySection.put(key, new LinkedHashMap<>());
        for (Menu m : all) {
            if (m.getParent() == null || m.getParent() == 0) continue;      // baris parent, bukan centangan
            String sec = m.getSection() == null ? "" : m.getSection();
            if (!bySection.containsKey(sec)) sec = "";
            String title = parentName.getOrDefault(m.getParent(), "Lainnya");
            bySection.get(sec).computeIfAbsent(title, k -> new ArrayList<>()).add(m);
        }

        List<AccessSection> out = new ArrayList<>();
        for (var e : bySection.entrySet()) {
            List<AccessGroup> groups = new ArrayList<>();
            for (var g : e.getValue().entrySet())
                groups.add(new AccessGroup(g.getKey(), g.getValue().get(0).getKind(), g.getValue()));
            if (!groups.isEmpty()) out.add(new AccessSection(SECTION_LABELS.get(e.getKey()), groups));
        }
        return out;
    }

    // Simpan hak akses menu -> config dual control (butuh alasan + approval Admin Approval).
    @PostMapping("/save")
    public String save(@RequestParam String user,
                       @RequestParam(name="menu", required=false) List<Integer> menuIds,
                       @RequestParam(required=false) String note,
                       @AuthenticationPrincipal AusyUserDetails me, RedirectAttributes ra) {
        ra.addFlashAttribute("ok", configChange.submitAccess(user, withBundledActions(menuIds), me, note));
        return "redirect:/access?user=" + user;
    }
}
