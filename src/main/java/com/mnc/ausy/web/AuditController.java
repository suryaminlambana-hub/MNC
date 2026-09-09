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
    private List<Integer> scopeFor(AusyUserDetails me) {
        return me == null ? List.of() : scope.visibleGroups(me.getIdGroup());
    }
    private boolean scoped(AusyUserDetails me) { return true; }   // selalu tersaring per sisi

    private List<TlogTransaction> transactions(AusyUserDetails me, Pageable page) {
        return tx.findByUserGroups(scopeFor(me), page);
    }

    private List<TlogActivity> activities(AusyUserDetails me, Pageable page) {
        return act.findByUserGroups(scopeFor(me), page);
    }

    @GetMapping("/audit/export")
    public void export(@AuthenticationPrincipal AusyUserDetails me, HttpServletResponse resp) throws Exception {
        // Sheet 1 — Transaksi
        List<List<Object>> txRows = new ArrayList<>();
        for (TlogTransaction t : transactions(me, PageRequest.of(0, 5000))) {
            txRows.add(Arrays.asList(t.getTimeActivity() == null ? "" : t.getTimeActivity().format(TS),
                    t.getUserId(), t.getNameActivity(), t.getTinfo()));
        }
        // Sheet 2 — Login / Aktivitas
        List<List<Object>> actRows = new ArrayList<>();
        for (TlogActivity a : activities(me, PageRequest.of(0, 5000))) {
            actRows.add(Arrays.asList(a.getTimeActivity() == null ? "" : a.getTimeActivity().format(TS),
                    a.getUserId(), a.getIp(), a.getNameActivity(), a.getInfo()));
        }
        excel.downloadSheets(resp, "Audit_Trail", List.of(
                new ExcelExportService.SheetData("Transaksi", List.of("Waktu", "User", "Aksi", "Detail"), txRows),
                new ExcelExportService.SheetData("Login", List.of("Waktu", "User", "IP", "Aktivitas", "Info"), actRows)
        ));
    }

    @GetMapping("/audit")
    public String audit(@AuthenticationPrincipal AusyUserDetails me, Model model) {
        model.addAttribute("transactions", transactions(me, PageRequest.of(0, 100)));
        model.addAttribute("activities", activities(me, PageRequest.of(0, 100)));
        model.addAttribute("scoped", scoped(me));
        model.addAttribute("scopeLabel", scope.label(me == null ? null : me.getIdGroup()));
        return "audit/index";
    }
}
