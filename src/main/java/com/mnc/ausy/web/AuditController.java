package com.mnc.ausy.web;

import com.mnc.ausy.domain.TlogActivity;
import com.mnc.ausy.domain.TlogTransaction;
import com.mnc.ausy.repo.TlogActivityRepository;
import com.mnc.ausy.repo.TlogTransactionRepository;
import com.mnc.ausy.security.AusyUserDetails;
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
 *  Scoped by role: Admin Approval (②) sees only Admin + Admin Approval (groups 1,2);
 *  Checker (④) sees only Maker + Checker (groups 3,4); other roles see all. */
@Controller
public class AuditController {
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("dd-MM-yyyy HH:mm:ss");
    private static final List<Integer> CONFIG_SIDE   = List.of(1, 2);   // admin + admin approval
    private static final List<Integer> BUSINESS_SIDE = List.of(3, 4);   // maker + checker
    private final TlogTransactionRepository tx;
    private final TlogActivityRepository act;
    private final ExcelExportService excel;

    public AuditController(TlogTransactionRepository tx, TlogActivityRepository act, ExcelExportService excel) {
        this.tx = tx; this.act = act; this.excel = excel;
    }

    /** Which groups this viewer may see in the audit trail; null = all. */
    private List<Integer> scopeFor(AusyUserDetails me) {
        if (me == null) return null;
        if (Integer.valueOf(2).equals(me.getIdGroup())) return CONFIG_SIDE;
        if (Integer.valueOf(4).equals(me.getIdGroup())) return BUSINESS_SIDE;
        return null;
    }
    private boolean scoped(AusyUserDetails me) { return scopeFor(me) != null; }

    private List<TlogTransaction> transactions(AusyUserDetails me, Pageable page) {
        List<Integer> s = scopeFor(me);
        return s != null ? tx.findByUserGroups(s, page) : tx.findAllByOrderByTimeActivityDesc(page);
    }

    private List<TlogActivity> activities(AusyUserDetails me, Pageable page) {
        List<Integer> s = scopeFor(me);
        return s != null ? act.findByUserGroups(s, page) : act.findAllByOrderByTimeActivityDesc(page);
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
        model.addAttribute("scopeLabel",
                Integer.valueOf(2).equals(me == null ? null : me.getIdGroup()) ? "Admin & Admin Approval" : "Maker & Checker");
        return "audit/index";
    }
}
