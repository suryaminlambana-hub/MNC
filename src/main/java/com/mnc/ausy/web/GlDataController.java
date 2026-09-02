package com.mnc.ausy.web;

import com.mnc.ausy.domain.GlTabel;
import com.mnc.ausy.repo.GlCodeRepository;
import com.mnc.ausy.repo.GlTabelRepository;
import com.mnc.ausy.security.AusyUserDetails;
import com.mnc.ausy.service.ApprovalService;
import com.mnc.ausy.service.AuditService;
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

    public GlDataController(GlTabelRepository gl, GlCodeRepository glCode, GlExportService glExport,
                            AuditService audit, GlChangeService changes, ApprovalService approval) {
        this.gl = gl; this.glCode = glCode; this.glExport = glExport; this.audit = audit;
        this.changes = changes; this.approval = approval;
    }

    /** A GL row is "registered" when its NoGL + D/C exists in the GL Code master. */
    private Set<String> registeredKeys() {
        return glCode.findAll().stream().map(c -> c.getCode() + "|" + c.getPosition()).collect(Collectors.toSet());
    }
    private static boolean isRegistered(Set<String> reg, GlTabel g) {
        return reg.contains(n(g.getGltrac()) + "|" + n(g.getGltrcd()));
    }
    private static String n(String s) { return s == null ? "" : s; }

    @GetMapping("/gl-legacy")
    public String list(@AuthenticationPrincipal AusyUserDetails me, Model model) {
        // Only GL yang aktif & sudah lolos otorisasi — exclude PENDING dan soft-deleted (active=0).
        List<GlTabel> rows = gl.findAll().stream()
                .filter(r -> r.getActive() != null && r.getActive() == 1 && !"PENDING".equals(r.getStatusDual()))
                .collect(Collectors.toList());
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
        model.addAttribute("exportableCount", glExport.exportableCount());
        model.addAttribute("exportablePakets", glExport.exportablePakets());
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
                                @AuthenticationPrincipal AusyUserDetails me, RedirectAttributes ra) {
        String msg = glExport.submitExport(me, note, pakets);
        ra.addFlashAttribute(msg.startsWith("Permintaan export diajukan") ? "ok" : "warn", msg);
        return "redirect:/gl-legacy";
    }

    @GetMapping("/gl-delete")
    public String deletePage(@AuthenticationPrincipal AusyUserDetails me, Model model) {
        // Sinkron dengan List GL: tampilkan batch GL aktif (sudah diotorisasi) agar bisa dihapus
        model.addAttribute("pakets", gl.approvedPakets());
        model.addAttribute("needApproval", approval.requiresApproval(me == null ? null : me.getIdGroup()));
        model.addAttribute("pendingKeys", changes.pendingPaketKeys());
        model.addAttribute("pendingRows", changes.pendingRowsByPaket());
        return "gl-delete/index";
    }

    /** BUG_AUSY_00082 — a maker's batch delete waits for the Checker; nothing is deleted until approved. */
    @PostMapping("/gl-delete/process")
    public String delete(@RequestParam String paket, @RequestParam String inputer,
                         @RequestParam(required=false) String note,
                         @AuthenticationPrincipal AusyUserDetails me, RedirectAttributes ra) {
        if (approval.requiresApproval(me.getIdGroup())) {
            GlChangeService.Res r = changes.submitPaketDelete(paket, inputer, me, note);
            ra.addFlashAttribute(r.ok() ? "ok" : "warn", r.message());
            return "redirect:/gl-delete";
        }
        java.util.List<Long> pendingRows = changes.pendingRowIdsInPaket(paket, inputer);
        if (!pendingRows.isEmpty()) {
            ra.addFlashAttribute("warn", "Batch " + paket + " tidak dapat dihapus: masih ada data yang menunggu otorisasi (ID "
                    + pendingRows.toString().replaceAll("[\\[\\]]", "") + ").");
            return "redirect:/gl-delete";
        }
        gl.deletePaket(paket, inputer);
        audit.transaction(me.getUsername(), "BATCH_DELETE", "Batch " + paket + " (maker=" + inputer + ") dihapus");
        ra.addFlashAttribute("ok", "Batch " + paket + " berhasil dihapus.");
        return "redirect:/gl-delete";
    }
}
