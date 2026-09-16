package com.mnc.ausy.web;

import com.mnc.ausy.domain.GroupMenu;
import com.mnc.ausy.domain.Menu;
import com.mnc.ausy.repo.*;
import com.mnc.ausy.security.AusyUserDetails;
import com.mnc.ausy.service.ApprovalService;
import com.mnc.ausy.service.ConfigChangeService;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.*;

@Controller
@RequestMapping("/access")
public class AccessController {
    private final GroupUserRepository groups;
    private final MenuRepository menus;
    private final GroupMenuRepository groupMenus;
    private final ConfigChangeService configChange;
    private final ApprovalService approval;

    public AccessController(GroupUserRepository groups, MenuRepository menus, GroupMenuRepository groupMenus,
                            ConfigChangeService configChange, ApprovalService approval) {
        this.approval = approval;
        this.groups = groups; this.menus = menus; this.groupMenus = groupMenus; this.configChange = configChange;
    }

    @GetMapping
    public String index(@RequestParam(required=false) Integer group, Model model,
                        @AuthenticationPrincipal AusyUserDetails me) {
        // Hak akses role yang mengotorisasi kita tidak boleh diubah dari sini (BUG_AUSY_00133)
        boolean iAmConfigApprover = approval.configAuthorizer(me == null ? null : me.getIdGroup());
        model.addAttribute("groups", groups.findAllByOrderByNamaGroupAsc().stream()
                .filter(g -> iAmConfigApprover || !approval.configAuthorizer(g.getIdGroup()))
                .toList());
        model.addAttribute("selectedGroup", group);
        model.addAttribute("pendingConfig", configChange.pendingListByMakerGroup(me == null ? null : me.getIdGroup()).size());
        model.addAttribute("pendingIds", configChange.pendingIds("ACCESS"));
        if (group != null) {
            Set<Integer> granted = new HashSet<>();
            for (GroupMenu gm : groupMenus.findByIdGroup(group)) granted.add(gm.getIdMenu());
            // hanya item aktif — menu lama yang sudah dilebur tidak perlu muncul sebagai centangan
            // Section Admin Approval hanya terlihat oleh Admin Approval sendiri (BUG_AUSY_00136):
            // aksesnya tidak boleh dibuka untuk role lain.
            List<Menu> all = menus.findAllByOrderByParentAscUrutanAsc().stream()
                    .filter(m -> m.getActive() != null && m.getActive() == 1)
                    .filter(m -> m.getBundledWith() == null)   // aksi yang menempel ikut menunya
                    .filter(m -> iAmConfigApprover || !"ADMIN_APV".equals(m.getSection()))
                    .toList();
            model.addAttribute("sections", sections(all));
            model.addAttribute("granted", granted);
        }
        return "access/index";
    }

    /**
     * Tambahkan aksi yang menempel pada menu yang dicentang. Tanpa ini, role penyetuju baru bisa
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
    /** Satu bagian per role di halaman Access Menu (BUG_AUSY_00115). */
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
     * Section hanya penataan tampilan — admin tetap bebas memberi item apa pun ke group mana pun.
     */
    private List<AccessSection> sections(List<Menu> all) {
        Map<Integer,String> parentName = new HashMap<>();
        for (Menu m : all) if (m.getParent() != null && m.getParent() == 0) parentName.put(m.getIdMenu(), m.getNamaMenu());

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
    public String save(@RequestParam Integer group,
                       @RequestParam(name="menu", required=false) List<Integer> menuIds,
                       @RequestParam(required=false) String note,
                       @AuthenticationPrincipal AusyUserDetails me, RedirectAttributes ra) {
        ra.addFlashAttribute("ok", configChange.submitAccess(group, withBundledActions(menuIds), me, note));
        return "redirect:/access?group=" + group;
    }
}
