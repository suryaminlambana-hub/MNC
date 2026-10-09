package com.mnc.ausy.web;

import com.mnc.ausy.service.BatchLabel;
import com.mnc.ausy.repo.*;
import com.mnc.ausy.security.AusyUserDetails;
import com.mnc.ausy.service.AccessService;
import com.mnc.ausy.service.AuditScopeService;
import com.mnc.ausy.service.ConfigChangeService;
import com.mnc.ausy.service.DataScopeService;
import com.mnc.ausy.service.GlChangeService;
import com.mnc.ausy.service.GlExportService;
import com.mnc.ausy.service.PasswordPolicyService;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

@Controller
public class DashboardController {

    private final UserRepository users;
    private final GroupUserRepository groups;
    private final GlTabelRepository gl;
    private final TlogTransactionRepository tx;
    private final GlChangeService glChange;
    private final ConfigChangeService configChange;
    private final GlExportService glExport;
    private final AccessService access;
    private final AuditScopeService auditScope;
    private final DataScopeService scope;
    private final PasswordPolicyService passwordPolicy;

    public DashboardController(UserRepository users, GroupUserRepository groups, GlTabelRepository gl,
                               TlogTransactionRepository tx, GlChangeService glChange,
                               ConfigChangeService configChange, GlExportService glExport,
                               AccessService access, AuditScopeService auditScope, DataScopeService scope,
                               PasswordPolicyService passwordPolicy) {
        this.users = users; this.groups = groups; this.gl = gl; this.tx = tx;
        this.glChange = glChange; this.configChange = configChange; this.glExport = glExport;
        this.access = access; this.auditScope = auditScope; this.scope = scope;
        this.passwordPolicy = passwordPolicy;
    }

    @GetMapping("/")
    public String root() { return "redirect:/dashboard"; }

    @GetMapping("/dashboard")
    public String dashboard(Model model, Authentication auth, @AuthenticationPrincipal AusyUserDetails principal) {
        // --- Admin / Config (1): only user & group config + user-change history (no GL/business) ---
        if (hasGroup(auth, "GROUP_1")) {
            Integer myGroup = principal == null ? null : principal.getIdGroup();
            model.addAttribute("totalUsers", users.count());
            model.addAttribute("totalGroups", groups.count());
            // sisi pengaju: yang ditampilkan adalah pengajuan group ini sendiri (BUG_AUSY_00128)
            List<ConfigChangeService.PendingUser> mine = configChange.pendingListByMakerGroup(myGroup);
            model.addAttribute("pendingUsers", mine.size());
            model.addAttribute("pendingUserList", mine);
            // hanya jejak admin ini sendiri, bukan seluruh admin (BUG_AUSY_00188)
            String meId = principal == null ? "" : principal.getUsername();
            model.addAttribute("userHistory", tx.configHistoryForAdmin(meId, "%(maker=" + meId + ")%", PageRequest.of(0, 50)));
            // Pengingat H-3 masa berlaku password (REQ_AUSY_00009). Sengaja TIDAK dipersempit
            // ke akun admin ini sendiri seperti Riwayat User di atas: ini daftar pekerjaan —
            // admin perlu tahu akun siapa pun yang passwordnya akan habis supaya bisa mereset.
            model.addAttribute("peringatanPassword", passwordPolicy.daftarPeringatan());
            return "dashboard-admin";
        }

        // --- Admin Approval (2): only user approvals + recent activity scoped to admin side ---
        if (hasGroup(auth, "GROUP_2")) {
            // sisi penyetuju: persis yang bisa dibuka di halaman Otorisasi (BUG_AUSY_00128)
            Integer myGroup = principal == null ? null : principal.getIdGroup();
            model.addAttribute("pendingUsers", configChange.countPendingFor(myGroup));
            model.addAttribute("pendingUserList", configChange.pendingListFor(myGroup));
            model.addAttribute("recent", tx.findByUserGroups(
                    auditScope.groupsVisibleTo(principal == null ? null : principal.getIdGroup(), AuditScopeService.CONFIG), PageRequest.of(0, 15)));
            return "dashboard-adminapv";
        }

        // --- Maker (3): task-focused home (no audit trail) ---
        if (hasGroup(auth, "GROUP_3")) {
            // Semua angka & daftar GL di beranda dibatasi ke data maker ini sendiri (BUG_AUSY_00138),
            // dan memakai definisi "GL Aktif" yang sama dengan halaman-halamannya (BUG_AUSY_00150).
            // Angkanya dari getTotalElements(). Daftar barisnya tidak diambil lagi: section
            // "Data GL Sudah Diotorisasi" dihapus dari beranda maker (REQ_AUSY_00051).
            Page<com.mnc.ausy.domain.GlTabel> aktif = gl.pageGlAktif(scope.visibleMakers(principal),
                    PageRequest.of(0, 1, Sort.by(Sort.Direction.DESC, "id")));
            model.addAttribute("activeGl", aktif.getTotalElements());
            // "Perubahan GL Pending" = upload paket + change request (add/edit/delete) + export GL, menunggu otorisasi
            List<Object[]> pendingPaketList = scope.filterArrays(principal, gl.pendingPakets(), 1);
            normalizeUploadedAt(pendingPaketList);
            List<com.mnc.ausy.domain.GlChangeRequest> pendingChangeList = glChange.pendingVisible(principal);
            List<com.mnc.ausy.domain.GlExportRequest> pendingExportList = glExport.allPending(principal);
            List<PendingRow> pendingList = pendingRows(pendingPaketList, pendingChangeList, pendingExportList);
            model.addAttribute("pendingList", pendingList);
            model.addAttribute("pendingChanges", pendingList.size());
            String me = auth.getName();
            model.addAttribute("glHistory", tx.glHistoryForMaker(me, "%maker=" + me + "%", PageRequest.of(0, 50)));
            return "dashboard-maker";
        }

        // --- Checker (4): only what needs authorizing + audit-trail recent (scoped Maker+Checker) ---
        if (hasGroup(auth, "GROUP_4")) {
            // "Paket Menunggu Otorisasi" = upload paket + export GL (keduanya di tab Otorisasi Upload/Export GL),
            // dibatasi ke maker yang dipasangkan pada checker ini (BUG_AUSY_00139).
            List<Object[]> pendingPaketList = scope.filterArrays(principal, gl.pendingPakets(), 1);
            List<com.mnc.ausy.domain.GlExportRequest> pendingExportList = glExport.pendingExportFor(principal);
            model.addAttribute("pendingPaketList", pendingPaketList);
            model.addAttribute("pendingExportList", pendingExportList);
            model.addAttribute("pendingPakets", pendingPaketList.size() + pendingExportList.size());
            model.addAttribute("pendingChanges", glChange.pendingVisibleCount(principal));
            model.addAttribute("pendingChangeList", glChange.pendingVisible(principal));
            model.addAttribute("recent", recentBusiness(principal, 10));
            return "dashboard-checker";
        }

        // --- Group lain (mis. group baru buatan admin): isinya murni dari Access Menu ---
        // Tidak ada card yang dicentang = dashboard kosong, bukan menampilkan semuanya
        // seperti sebelumnya (BUG_AUSY_00112). Query pun hanya dijalankan untuk card yang diberikan.
        java.util.Set<String> cards = access.cardsForUser(principal == null ? null : principal.getUsername());
        if (cards.contains("cfg-stats")) {
            model.addAttribute("totalUsers", users.count());
            model.addAttribute("totalGroups", groups.count());
        }
        // Card "Baris GL Aktif" memakai definisi yang sama dengan halamannya: baris yang masih
        // menunggu otorisasi tidak ikut dihitung (BUG_AUSY_00150).
        if (cards.contains("gl-active"))
            model.addAttribute("activeGl", gl.pageGlAktif(scope.visibleMakers(principal),
                    PageRequest.of(0, 1)).getTotalElements());
        if (cards.contains("otorisasi-pending"))
            model.addAttribute("pendingPakets", scope.filterArrays(principal, gl.pendingPakets(), 1).size());
        if (cards.contains("gl-change-pending"))
            model.addAttribute("pendingChanges", glChange.pendingVisibleCount(principal));
        // card aktivitas terbaru ada dua: operasional & administratif, masing-masing hak aksesnya sendiri
        if (cards.contains("cfg-pending")) {
            Integer myGroup = principal == null ? null : principal.getIdGroup();
            // role penyetuju config buatan admin: isinya yang benar-benar boleh dia setujui
            List<ConfigChangeService.PendingUser> queue = configChange.isApproverForAnyone(myGroup)
                    ? configChange.pendingListFor(myGroup)
                    : configChange.pendingListByMakerGroup(myGroup);
            model.addAttribute("pendingUsers", queue.size());
            model.addAttribute("pendingUserList", queue);
        }
        if (cards.contains("audit-recent"))
            model.addAttribute("recent", recentBusiness(principal, 8));
        else if (cards.contains("audit-recent-admin"))
            model.addAttribute("recent", tx.findByUserGroups(
                    auditScope.groupsVisibleTo(principal == null ? null : principal.getIdGroup(), AuditScopeService.CONFIG), PageRequest.of(0, 8)));
        model.addAttribute("noCards", cards.isEmpty());
        return "dashboard";
    }

    /**
     * Aktivitas terakhir sisi operasional, dengan aturan yang SAMA dengan halaman Audit Trail
     * (BUG_AUSY_00192): checker hanya melihat jejaknya sendiri dan maker pasangannya, maker hanya
     * jejaknya sendiri. Dulu card ini menyaring per group, sehingga checker melihat semua maker
     * dan checker lain, sementara halaman Audit Trail-nya sudah dibatasi (BUG_AUSY_00149).
     */
    private List<com.mnc.ausy.domain.TlogTransaction> recentBusiness(AusyUserDetails me, int n) {
        java.util.Set<String> who = scope.visibleMakers(me);
        if (who == null)                                   // admin / monitoring: per group seperti halamannya
            return tx.findByUserGroups(auditScope.groupsVisibleTo(me == null ? null : me.getIdGroup(),
                    AuditScopeService.BUSINESS), PageRequest.of(0, n));
        if (who.isEmpty()) return List.of();               // tidak ada pasangan: kosong, bukan "semua"
        return tx.findByUserIds(who, PageRequest.of(0, n));
    }

    private boolean hasGroup(Authentication auth, String authority) {
        return auth != null && auth.getAuthorities().stream()
                .anyMatch(a -> authority.equals(a.getAuthority()));
    }

    /** pendingPakets()'s last column (max(adddt)) may come back as java.sql.Timestamp or
     *  java.time.LocalDateTime depending on driver/Hibernate version — Thymeleaf's #temporals
     *  only accepts the latter, so normalize it in place before it reaches the template. */
    /** One row of the maker's "Perubahan GL Pending" list, whatever kind of request it came from. */
    public record PendingRow(LocalDateTime time, String kind, String badge, String target, String note) {}

    /**
     * Upload batches, GL change requests and export requests shown as ONE list, newest first —
     * three separately-sorted blocks used to read as jumbled dates (BUG_AUSY_00092).
     */
    private static List<PendingRow> pendingRows(List<Object[]> pakets,
                                                List<com.mnc.ausy.domain.GlChangeRequest> changes,
                                                List<com.mnc.ausy.domain.GlExportRequest> exports) {
        List<PendingRow> out = new ArrayList<>();
        if (pakets != null) for (Object[] p : pakets) {
            out.add(new PendingRow(p[5] instanceof LocalDateTime t ? t : null, "UPLOAD", "bg-info-subtle text-info",
                    "Batch " + BatchLabel.label(String.valueOf(p[0])) + " · " + p[2] + " baris", "Upload GL menunggu otorisasi"));
        }
        if (changes != null) for (var c : changes) {
            String kind = "BULK_EDIT".equals(c.getOp()) ? "BULK EDIT" : c.getOp();
            String badge = switch (kind) {
                case "ADD", "IMPORT" -> "bg-success-subtle text-success";
                case "DELETE"        -> "bg-danger-subtle text-danger";
                default              -> "bg-warning-subtle text-warning";
            };
            String target;
            if ("GL_CODE".equals(c.getTarget())) target = "Kode GL";
            else if ("PAKET".equals(c.getTarget())) target = "Batch " + BatchLabel.label(c.getNoPaket());
            else {
                target = c.getGlId() != null ? "GL #" + c.getGlId() : "Data GL";
                if (c.getNoPaket() != null && !c.getNoPaket().isBlank()) target += " · Batch " + BatchLabel.label(c.getNoPaket());
            }
            out.add(new PendingRow(c.getCreatedAt(), kind, badge, target, c.getNoteMaker()));
        }
        if (exports != null) for (var e : exports) {
            out.add(new PendingRow(e.getCreatedAt(), "EXPORT", "bg-info-subtle text-info",
                    "Export #" + e.getId() + " · " + e.getRowsCount() + " baris", e.getNoteMaker()));
        }
        // terbaru dulu; yang tanpa waktu ditaruh paling bawah
        out.sort(Comparator.comparing(PendingRow::time,
                Comparator.nullsLast(Comparator.reverseOrder())));
        return out;
    }

    private static void normalizeUploadedAt(List<Object[]> rows) {
        for (Object[] r : rows) {
            if (r[5] instanceof java.sql.Timestamp ts) r[5] = ts.toLocalDateTime();
        }
    }
}
