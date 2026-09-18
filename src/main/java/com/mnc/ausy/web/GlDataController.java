package com.mnc.ausy.web;

import com.mnc.ausy.domain.GlTabel;
import com.mnc.ausy.repo.GlCodeRepository;
import com.mnc.ausy.repo.GlTabelRepository;
import com.mnc.ausy.security.AusyUserDetails;
import com.mnc.ausy.service.ApprovalService;
import com.mnc.ausy.service.AuditService;
import com.mnc.ausy.service.DataScopeService;
import com.mnc.ausy.service.GlChangeService;
import com.mnc.ausy.service.GlExportService;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.math.BigDecimal;
import java.util.*;
import java.util.stream.Collectors;

@Controller
public class GlDataController {
    private final GlTabelRepository gl;
    private final GlCodeRepository glCode;
    private final GlExportService glExport;
    private final AuditService audit;
    private final GlChangeService changes;
    private final ApprovalService approval;
    private final DataScopeService scope;

    public GlDataController(GlTabelRepository gl, GlCodeRepository glCode, GlExportService glExport,
                            AuditService audit, GlChangeService changes, ApprovalService approval,
                            DataScopeService scope) {
        this.gl = gl; this.glCode = glCode; this.glExport = glExport; this.audit = audit;
        this.changes = changes; this.approval = approval; this.scope = scope;
    }

    /** A GL row is "registered" when its NoGL + D/C exists in the GL Code master. */
    private Set<String> registeredKeys() {
        return glCode.findAll().stream().map(c -> c.getCode() + "|" + c.getPosition()).collect(Collectors.toSet());
    }
    private static boolean isRegistered(Set<String> reg, GlTabel g) {
        return reg.contains(n(g.getGltrac()) + "|" + n(g.getGltrcd()));
    }
    private static String n(String s) { return s == null ? "" : s; }

    /**
     * Only the Business Maker (group 3) may request an export — same rule SecurityConfig applies to
     * /gl-legacy/export-request. The Checker shares this read-only page but has no export rights
     * (BUG_AUSY_00097), so the button and the export history stay hidden for them.
     */
    private static boolean canExport(AusyUserDetails me) {
        return me != null && me.getAuthorities().stream()
                .anyMatch(a -> "GROUP_3".equals(a.getAuthority()));
    }

    @GetMapping("/gl-legacy")
    public String list(@AuthenticationPrincipal AusyUserDetails me, Model model) {
        // Only GL yang aktif & sudah lolos otorisasi — exclude PENDING dan soft-deleted (active=0).
        // Isinya dibatasi per maker: maker hanya melihat datanya sendiri, checker melihat data
        // maker yang dipasangkan padanya (BUG_AUSY_00138 / BUG_AUSY_00139).
        List<GlTabel> rows = scope.filterRows(me, gl.findAll().stream()
                .filter(r -> r.getActive() != null && r.getActive() == 1 && !"PENDING".equals(r.getStatusDual()))
                .collect(Collectors.toList()));
        Set<String> reg = registeredKeys();
        long registeredCount = rows.stream().filter(r -> isRegistered(reg, r)).count();
        BigDecimal totalAmount = rows.stream()
                .map(r -> r.getGltram() == null ? BigDecimal.ZERO : r.getGltram())
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        model.addAttribute("rows", rows);
        model.addAttribute("registered", reg);
        model.addAttribute("total", rows.size());
        model.addAttribute("totalRegistered", registeredCount);
        model.addAttribute("totalAmount", totalAmount);
        boolean canExport = canExport(me);
        model.addAttribute("canExport", canExport);
        model.addAttribute("exportableCount", glExport.exportableCount(me));
        model.addAttribute("exportablePakets", glExport.exportablePakets(me));
        model.addAttribute("exportableManual", glExport.exportableManualRows(me));   // BUG_AUSY_00124/00125
        model.addAttribute("pendingPakets", glExport.pendingPaketKeys());   // BUG_AUSY_00102
        model.addAttribute("myPendingExports", glExport.myPending(me.getUsername()));
        model.addAttribute("exportHistory", glExport.myHistory(me.getUsername()));
        return "gl-legacy/list";
    }

    /**
     * Maker "Ajukan Export": ajukan paket GL aktif pilihan maker ke checker (Otorisasi Export GL).
     * File ACCTXT tidak diunduh ke komputer — dikirim ke FTP setelah checker approve.
     */
    @PostMapping("/gl-legacy/export-request")
    public String exportRequest(@RequestParam String note, @RequestParam(required = false) List<String> pakets,
                                @RequestParam(required = false) List<Long> glIds,
                                @AuthenticationPrincipal AusyUserDetails me, RedirectAttributes ra) {
        String msg = glExport.submitExport(me, note, pakets, glIds);
        ra.addFlashAttribute(msg.startsWith("Permintaan export diajukan") ? "ok" : "warn", msg);
        return "redirect:/gl-legacy";
    }

    @GetMapping("/gl-delete")
    public String deletePage(@AuthenticationPrincipal AusyUserDetails me, Model model) {
        // Sinkron dengan List GL: tampilkan batch GL aktif (sudah diotorisasi) agar bisa dihapus —
        // hanya batch milik maker ini sendiri (BUG_AUSY_00138). Kolom ke-2 = gltrus.
        model.addAttribute("pakets", scope.filterArrays(me, gl.deletablePakets(), 1));   // tanpa data tambahan manual (BUG_AUSY_00126)
        model.addAttribute("needApproval", true);   // dual control wajib (BUG_AUSY_00116)
        model.addAttribute("pendingKeys", changes.pendingPaketKeys());
        model.addAttribute("pendingRows", changes.pendingRowsByPaket());
        model.addAttribute("exportPending", changes.exportPendingPakets());   // BUG_AUSY_00106
        return "gl-delete/index";
    }

    /** BUG_AUSY_00082 — a maker's batch delete waits for the Checker; nothing is deleted until approved. */
    @PostMapping("/gl-delete/process")
    public String delete(@RequestParam String paket, @RequestParam String inputer,
                         @RequestParam(required=false) String note,
                         @AuthenticationPrincipal AusyUserDetails me, RedirectAttributes ra) {
        // Batch milik maker lain tidak boleh diajukan hapus, walau nomornya ditebak (BUG_AUSY_00138).
        if (!scope.canSee(me, inputer)) {
            ra.addFlashAttribute("warn", DataScopeService.NOT_MY_MAKER);
            return "redirect:/gl-delete";
        }
        // Selalu lewat maker->checker. Kalau group belum punya penyetuju aktif, pengajuan ditolak
        // di service dengan pesannya sendiri — data TIDAK dihapus langsung (BUG_AUSY_00116).
        GlChangeService.Res r = changes.submitPaketDelete(paket, inputer, me, note);
        ra.addFlashAttribute(r.ok() ? "ok" : "warn", r.message());
        return "redirect:/gl-delete";
    }
}
