package com.mnc.ausy.web;

import com.mnc.ausy.domain.TlogActivity;
import com.mnc.ausy.domain.TlogTransaction;
import com.mnc.ausy.repo.TlogActivityRepository;
import com.mnc.ausy.repo.TlogTransactionRepository;
import com.mnc.ausy.security.AusyUserDetails;
import com.mnc.ausy.service.AuditScopeService;
import com.mnc.ausy.service.ExcelExportService;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

import java.time.format.DateTimeFormatter;
import java.util.*;

/** Immutable audit-trail viewer (read-only) — PDF §7.3 / §8 / §9.
 *  Isinya terpisah per sisi kerja: yang memegang menu administratif hanya melihat jejak
 *  Admin & Admin Approval, yang memegang menu operasional hanya melihat jejak Maker & Checker.
 *  Sisi itu dibaca dari hak akses group (AccessService.sideOf), bukan dari id group yang
 *  ditulis di kode, supaya group buatan admin ikut tersaring dengan benar (BUG_AUSY_00115). */
@Controller
public class AuditController {
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("dd-MM-yyyy HH:mm:ss");
    private final TlogTransactionRepository tx;
    private final TlogActivityRepository act;
    private final ExcelExportService excel;
    private final AuditScopeService scope;

    public AuditController(TlogTransactionRepository tx, TlogActivityRepository act, ExcelExportService excel,
                           AuditScopeService scope) {
        this.tx = tx; this.act = act; this.excel = excel; this.scope = scope;
    }

    /** Group mana saja yang boleh dilihat jejaknya oleh viewer ini — selalu tersaring per sisi. */
    /* Dua halaman terpisah, masing-masing hak aksesnya sendiri di Access Menu:
       /audit       -> jejak Maker & Checker      (bagian Checker)
       /audit-admin -> jejak Admin & Admin Approval (bagian Admin Approval)
       Isinya ditentukan halaman yang dibuka, bukan ditebak dari group pembacanya. */

    @GetMapping("/audit")
    public String auditBusiness(Model model) { return render(AuditScopeService.BUSINESS, model); }

    @GetMapping("/audit-admin")
    public String auditConfig(Model model) { return render(AuditScopeService.CONFIG, model); }

    @GetMapping("/audit/export")
    public void exportBusiness(HttpServletResponse resp) throws Exception {
        export(AuditScopeService.BUSINESS, resp);
    }

    @GetMapping("/audit-admin/export")
    public void exportConfig(HttpServletResponse resp) throws Exception {
        export(AuditScopeService.CONFIG, resp);
    }

    private String render(String side, Model model) {
        List<Integer> groups = scope.groupsOnSide(side);
        model.addAttribute("transactions", tx.findByUserGroups(groups, PageRequest.of(0, 100)));
        model.addAttribute("activities", act.findByUserGroups(groups, PageRequest.of(0, 100)));
        model.addAttribute("scoped", true);
        model.addAttribute("scopeLabel", scope.label(side));
        model.addAttribute("exportUrl", AuditScopeService.CONFIG.equals(side) ? "/audit-admin/export" : "/audit/export");
        return "audit/index";
    }

    private void export(String side, HttpServletResponse resp) throws Exception {
        List<Integer> groups = scope.groupsOnSide(side);
        List<List<Object>> txRows = new ArrayList<>();
        for (TlogTransaction t : tx.findByUserGroups(groups, PageRequest.of(0, 5000)))
            txRows.add(Arrays.asList(t.getTimeActivity() == null ? "" : t.getTimeActivity().format(TS),
                    t.getUserId(), t.getNameActivity(), t.getTinfo()));
        List<List<Object>> actRows = new ArrayList<>();
        for (TlogActivity a : act.findByUserGroups(groups, PageRequest.of(0, 5000)))
            actRows.add(Arrays.asList(a.getTimeActivity() == null ? "" : a.getTimeActivity().format(TS),
                    a.getUserId(), a.getIp(), a.getNameActivity(), a.getInfo()));
        String name = AuditScopeService.CONFIG.equals(side) ? "Audit_Trail_Admin" : "Audit_Trail_Maker_Checker";
        excel.downloadSheets(resp, name, List.of(
                new ExcelExportService.SheetData("Transaksi", List.of("Waktu", "User", "Aksi", "Detail"), txRows),
                new ExcelExportService.SheetData("Login", List.of("Waktu", "User", "IP", "Aktivitas", "Info"), actRows)
        ));
    }
}
