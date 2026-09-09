package com.mnc.ausy.web;

import com.mnc.ausy.repo.*;
import com.mnc.ausy.security.AusyUserDetails;
import com.mnc.ausy.service.AccessService;
import com.mnc.ausy.service.AuditScopeService;
import com.mnc.ausy.service.ConfigChangeService;
import com.mnc.ausy.service.FtpAdminService;
import com.mnc.ausy.service.GlChangeService;
import com.mnc.ausy.service.GlExportService;
import org.springframework.data.domain.PageRequest;
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
    private final FtpAdminService ftpAdmin;
    private final AccessService access;
    private final AuditScopeService auditScope;

    public DashboardController(UserRepository users, GroupUserRepository groups, GlTabelRepository gl,
                               TlogTransactionRepository tx, GlChangeService glChange,
                               ConfigChangeService configChange, GlExportService glExport, FtpAdminService ftpAdmin,
                               AccessService access, AuditScopeService auditScope) {
        this.users = users; this.groups = groups; this.gl = gl; this.tx = tx;
        this.glChange = glChange; this.configChange = configChange; this.glExport = glExport; this.ftpAdmin = ftpAdmin;
        this.access = access; this.auditScope = auditScope;
    }

    @GetMapping("/")
    public String root() { return "redirect:/dashboard"; }

    @GetMapping("/dashboard")
    public String dashboard(Model model, Authentication auth, @AuthenticationPrincipal AusyUserDetails principal) {
        // --- Admin / Config (1): only user & group config + user-change history (no GL/business) ---
        if (hasGroup(auth, "GROUP_1")) {
            model.addAttribute("totalUsers", users.count());
            model.addAttribute("totalGroups", groups.count());
            model.addAttribute("pendingUsers", configChange.countPending());
            model.addAttribute("pendingUserList", configChange.pendingList());
            model.addAttribute("userHistory", tx.byActivityPrefix("CONFIG_CHANGE%", PageRequest.of(0, 50)));
            // FTP transfers stuck as PENDING/FAILED — Admin needs to resend from Konfigurasi FTP.
            List<com.mnc.ausy.domain.ExportTransfer> ftpResendList = ftpAdmin.needsResend();
            model.addAttribute("ftpResendList", ftpResendList);
            model.addAttribute("ftpResendCount", ftpResendList.size());
            return "dashboard-admin";
        }

        // --- Admin Approval (2): only user approvals + recent activity scoped to admin side ---
        if (hasGroup(auth, "GROUP_2")) {
            model.addAttribute("pendingUsers", configChange.countPending());
            model.addAttribute("pendingUserList", configChange.pendingList());
            model.addAttribute("recent", tx.findByUserGroups(
                    auditScope.visibleGroups(principal == null ? null : principal.getIdGroup()), PageRequest.of(0, 15)));
            return "dashboard-adminapv";
        }

        // --- Maker (3): task-focused home (no audit trail) ---
        if (hasGroup(auth, "GROUP_3")) {
            model.addAttribute("activeGl", gl.countByActiveAndStatusDualNot((short) 1, "PENDING"));
            model.addAttribute("glActiveList", gl.findTop200ByActiveAndStatusDualNotOrderByIdDesc((short) 1, "PENDING"));
            // "Perubahan GL Pending" = upload paket + change request (add/edit/delete) + export GL, menunggu otorisasi
            List<Object[]> pendingPaketList = gl.pendingPakets();
            normalizeUploadedAt(pendingPaketList);
            List<com.mnc.ausy.domain.GlChangeRequest> pendingChangeList = glChange.pendingVisible();
            List<com.mnc.ausy.domain.GlExportRequest> pendingExportList = glExport.allPending();
            List<PendingRow> pendingList = pendingRows(pendingPaketList, pendingChangeList, pendingExportList);
            model.addAttribute("pendingList", pendingList);
            model.addAttribute("pendingChanges", pendingList.size());
            String me = auth.getName();
            model.addAttribute("glHistory", tx.glHistoryForMaker(me, "%maker=" + me + "%", PageRequest.of(0, 50)));
            // Export yang sudah di-approve checker tapi gagal terkirim ke FTP — maker perlu tahu & minta Admin kirim ulang.
            List<com.mnc.ausy.domain.ExportTransfer> ftpFailedList = ftpAdmin.failedFor(me);
            model.addAttribute("ftpFailedList", ftpFailedList);
            model.addAttribute("ftpFailedCount", ftpFailedList.size());
            return "dashboard-maker";
        }

        // --- Checker (4): only what needs authorizing + audit-trail recent (scoped Maker+Checker) ---
        if (hasGroup(auth, "GROUP_4")) {
            // "Paket Menunggu Otorisasi" = upload paket + export GL (keduanya di tab Otorisasi Upload/Export GL)
            List<Object[]> pendingPaketList = gl.pendingPakets();
            List<com.mnc.ausy.domain.GlExportRequest> pendingExportList = glExport.pendingExportFor(principal.getIdGroup());
            model.addAttribute("pendingPaketList", pendingPaketList);
            model.addAttribute("pendingExportList", pendingExportList);
            model.addAttribute("pendingPakets", pendingPaketList.size() + pendingExportList.size());
            model.addAttribute("pendingChanges", glChange.pendingVisibleCount());
            model.addAttribute("pendingChangeList", glChange.pendingVisible());
            model.addAttribute("recent", tx.findByUserGroups(
                    auditScope.visibleGroups(principal.getIdGroup()), PageRequest.of(0, 10)));
            return "dashboard-checker";
        }

        // --- Group lain (mis. group baru buatan admin): isinya murni dari Access Menu ---
        // Tidak ada card yang dicentang = dashboard kosong, bukan menampilkan semuanya
        // seperti sebelumnya (BUG_AUSY_00112). Query pun hanya dijalankan untuk card yang diberikan.
        java.util.Set<String> cards = access.cardsFor(principal == null ? null : principal.getIdGroup());
        if (cards.contains("cfg-stats")) {
            model.addAttribute("totalUsers", users.count());
            model.addAttribute("totalGroups", groups.count());
        }
        if (cards.contains("gl-active")) model.addAttribute("activeGl", gl.countByActive((short) 1));
        if (cards.contains("otorisasi-pending")) model.addAttribute("pendingPakets", gl.pendingPakets().size());
        if (cards.contains("gl-change-pending")) model.addAttribute("pendingChanges", glChange.pendingVisibleCount());
        if (cards.contains("audit-recent"))     // ikut tersaring per sisi, bukan semua log
            model.addAttribute("recent", tx.findByUserGroups(
                    auditScope.visibleGroups(principal == null ? null : principal.getIdGroup()), PageRequest.of(0, 8)));
        if (cards.contains("volume-tx")) {
            List<Object[]> daily = tx.dailyCounts();
            long max = 1;
            for (Object[] r : daily) max = Math.max(max, ((Number) r[1]).longValue());
            model.addAttribute("chart", daily);
            model.addAttribute("chartMax", max);
        }
        model.addAttribute("noCards", cards.isEmpty());
        return "dashboard";
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
                    "Batch " + p[0] + " · " + p[2] + " baris", "Upload GL menunggu otorisasi"));
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
            else if ("PAKET".equals(c.getTarget())) target = "Batch " + c.getNoPaket();
            else {
                target = c.getGlId() != null ? "GL #" + c.getGlId() : "Data GL";
                if (c.getNoPaket() != null && !c.getNoPaket().isBlank()) target += " · Batch " + c.getNoPaket();
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
