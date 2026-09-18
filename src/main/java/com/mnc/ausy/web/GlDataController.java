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
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
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

    /** Hasil agregat multi-kolom kadang dibungkus Object[1][] oleh JPA. */
    private static Object[] unwrapRow(Object[] row) {
        return (row != null && row.length == 1 && row[0] instanceof Object[] inner) ? inner : row;
    }

    /**
     * Only the Business Maker (group 3) may request an export — same rule SecurityConfig applies to
     * /gl-legacy/export-request. The Checker shares this read-only page but has no export rights
     * (BUG_AUSY_00097), so the button and the export history stay hidden for them.
     */
    private static boolean canExport(AusyUserDetails me) {
        return me != null && me.getAuthorities().stream()
                .anyMatch(a -> "GROUP_3".equals(a.getAuthority()));
    }

    /** Baris per halaman di List GL Aktif — datanya bisa belasan ribu (BUG_AUSY_00147). */
    private static final int PAGE_SIZE = 10;

    @GetMapping("/gl-legacy")
    public String list(@AuthenticationPrincipal AusyUserDetails me, Model model,
                       @RequestParam(defaultValue = "0") int page) {
        // GL aktif & sudah lolos otorisasi, DIAMBIL PER HALAMAN dari database. Sebelumnya seluruh
        // tabel dimuat lalu disaring di Java, sehingga 12 ribu baris dirender sekaligus dan
        // halamannya blank saat di-scroll (BUG_AUSY_00147).
        // Isinya tetap dibatasi per maker: maker hanya melihat datanya sendiri, checker melihat
        // data maker yang dipasangkan padanya (BUG_AUSY_00138 / BUG_AUSY_00139).
        Set<String> makers = scope.visibleMakers(me);
        Pageable req = PageRequest.of(Math.max(page, 0), PAGE_SIZE, Sort.by(Sort.Direction.DESC, "id"));
        Page<GlTabel> result;
        Object[] sum;
        if (makers == null) {
            result = gl.pageActive(req);
            sum = unwrapRow(gl.activeSummary());
        } else if (makers.isEmpty()) {
            result = Page.empty(req);
            sum = new Object[]{0L, BigDecimal.ZERO, 0L};
        } else {
            result = gl.pageActiveByMakers(makers, req);
            sum = unwrapRow(gl.activeSummaryByMakers(makers));
        }
        Set<String> reg = registeredKeys();
        model.addAttribute("rows", result.getContent());
        model.addAttribute("registered", reg);
        model.addAttribute("total", ((Number) sum[0]).longValue());
        model.addAttribute("totalRegistered", sum[2] == null ? 0L : ((Number) sum[2]).longValue());
        model.addAttribute("totalAmount", sum[1] == null ? BigDecimal.ZERO : (BigDecimal) sum[1]);
        model.addAttribute("page", result.getNumber());
        model.addAttribute("totalPages", Math.max(result.getTotalPages(), 1));
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
