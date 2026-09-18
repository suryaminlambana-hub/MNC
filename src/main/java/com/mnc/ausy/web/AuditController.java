package com.mnc.ausy.web;

import com.mnc.ausy.domain.TlogActivity;
import com.mnc.ausy.domain.TlogTransaction;
import com.mnc.ausy.repo.TlogActivityRepository;
import com.mnc.ausy.repo.TlogTransactionRepository;
import com.mnc.ausy.security.AusyUserDetails;
import com.mnc.ausy.service.AuditScopeService;
import com.mnc.ausy.service.DataScopeService;
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
    private final DataScopeService dataScope;

    public AuditController(TlogTransactionRepository tx, TlogActivityRepository act, ExcelExportService excel,
                           AuditScopeService scope, DataScopeService dataScope) {
        this.dataScope = dataScope;
        this.tx = tx; this.act = act; this.excel = excel; this.scope = scope;
    }

    /** Group mana saja yang boleh dilihat jejaknya oleh viewer ini — selalu tersaring per sisi. */
    /* Dua halaman terpisah, masing-masing hak aksesnya sendiri di Access Menu:
       /audit       -> jejak Maker & Checker      (bagian Checker)
       /audit-admin -> jejak Admin & Admin Approval (bagian Admin Approval)
       Isinya ditentukan halaman yang dibuka, bukan ditebak dari group pembacanya. */

    @GetMapping("/audit")
    public String auditBusiness(Model model, @AuthenticationPrincipal AusyUserDetails me) {
        return render(AuditScopeService.BUSINESS, model, me);
    }

    @GetMapping("/audit-admin")
    public String auditConfig(Model model, @AuthenticationPrincipal AusyUserDetails me) {
        return render(AuditScopeService.CONFIG, model, me);
    }

    @GetMapping("/audit/export")
    public void exportBusiness(HttpServletResponse resp, @AuthenticationPrincipal AusyUserDetails me) throws Exception {
        export(AuditScopeService.BUSINESS, resp, me);
    }

    @GetMapping("/audit-admin/export")
    public void exportConfig(HttpServletResponse resp, @AuthenticationPrincipal AusyUserDetails me) throws Exception {
        export(AuditScopeService.CONFIG, resp, me);
    }

    private String render(String side, Model model, AusyUserDetails me) {
        // Sisi operasional: isinya mengikuti pasangan Checker - Maker, jadi checker tidak melihat
        // aktivitas maker di luar naungannya (BUG_AUSY_00149).
        Set<String> users = AuditScopeService.BUSINESS.equals(side) ? dataScope.visibleMakers(me) : null;
        if (users != null) {
            if (users.isEmpty()) users = Set.of("\u0000");     // tidak ada pasangan: kosongkan
            model.addAttribute("transactions", tx.findByUserIds(users, PageRequest.of(0, 100)));
            model.addAttribute("activities", act.findByUserIds(users, PageRequest.of(0, 100)));
        } else {
            List<Integer> groups = scope.groupsVisibleTo(me == null ? null : me.getIdGroup(), side);
            model.addAttribute("transactions", tx.findByUserGroups(groups, PageRequest.of(0, 100)));
            model.addAttribute("activities", act.findByUserGroups(groups, PageRequest.of(0, 100)));
        }
        model.addAttribute("scoped", true);
        model.addAttribute("scopeLabel", scope.label(side));
        model.addAttribute("exportUrl", AuditScopeService.CONFIG.equals(side) ? "/audit-admin/export" : "/audit/export");
        return "audit/index";
    }

    private void export(String side, HttpServletResponse resp, AusyUserDetails me) throws Exception {
        Set<String> users = AuditScopeService.BUSINESS.equals(side) ? dataScope.visibleMakers(me) : null;
        if (users != null && users.isEmpty()) users = Set.of("\u0000");
        List<Integer> groups = users != null ? List.of()
                : scope.groupsVisibleTo(me == null ? null : me.getIdGroup(), side);
        List<List<Object>> txRows = new ArrayList<>();
        for (TlogTransaction t : (users != null ? tx.findByUserIds(users, PageRequest.of(0, 5000))
                                                : tx.findByUserGroups(groups, PageRequest.of(0, 5000))))
            txRows.add(Arrays.asList(t.getTimeActivity() == null ? "" : t.getTimeActivity().format(TS),
                    t.getUserId(), t.getNameActivity(), t.getTinfo()));
        List<List<Object>> actRows = new ArrayList<>();
        for (TlogActivity a : (users != null ? act.findByUserIds(users, PageRequest.of(0, 5000))
                                             : act.findByUserGroups(groups, PageRequest.of(0, 5000))))
            actRows.add(Arrays.asList(a.getTimeActivity() == null ? "" : a.getTimeActivity().format(TS),
                    a.getUserId(), a.getIp(), a.getNameActivity(), a.getInfo()));
        String name = AuditScopeService.CONFIG.equals(side) ? "Audit_Trail_Admin" : "Audit_Trail_Maker_Checker";
        // Nama sheet mengikuti nama tab di halaman Audit Trail (BUG_AUSY_00144): sheet pertama
        // berisi tab "Aktivitas Terakhir", bukan lagi ditulis "Transaksi" yang tidak ada di layar.
        // Tab kedua "Aktivitas / Login" ditulis tanpa garis miring — karakter itu terlarang di
        // nama sheet Excel dan akan membuat file gagal dibuat.
        excel.downloadSheets(resp, name, List.of(
                new ExcelExportService.SheetData("Aktivitas Terakhir", List.of("Waktu", "User", "Aksi", "Detail"), txRows),
                new ExcelExportService.SheetData("Aktivitas Login", List.of("Waktu", "User", "IP", "Aktivitas", "Info"), actRows)
        ));
    }
}
