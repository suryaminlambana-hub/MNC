package com.mnc.ausy.web;

import com.mnc.ausy.domain.TlogActivity;
import com.mnc.ausy.domain.TlogTransaction;
import com.mnc.ausy.repo.TlogActivityRepository;
import com.mnc.ausy.repo.TlogTransactionRepository;
import com.mnc.ausy.domain.GroupUser;
import com.mnc.ausy.repo.GroupUserRepository;
import com.mnc.ausy.security.AusyUserDetails;
import com.mnc.ausy.service.AccessService;
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
    private final GroupUserRepository groups;
    private final AccessService access;

    public AuditController(TlogTransactionRepository tx, TlogActivityRepository act, ExcelExportService excel,
                           GroupUserRepository groups, AccessService access) {
        this.tx = tx; this.act = act; this.excel = excel; this.groups = groups; this.access = access;
    }

    /** Group mana saja yang boleh dilihat jejaknya oleh viewer ini; null = semua. */
    private List<Integer> scopeFor(AusyUserDetails me) {
        if (me == null) return null;
        String side = access.sideOf(me.getIdGroup());
        if (side == null) return null;                       // memegang dua sisi: lihat semua
        List<Integer> out = new ArrayList<>();
        for (GroupUser g : groups.findAll())
            if (side.equals(access.sideOf(g.getIdGroup()))) out.add(g.getIdGroup());
        if (!out.contains(me.getIdGroup())) out.add(me.getIdGroup());
        return out;
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
        String side = access.sideOf(me == null ? null : me.getIdGroup());
        model.addAttribute("scopeLabel",
                "CONFIG".equals(side) ? "Admin & Admin Approval" : "BUSINESS".equals(side) ? "Maker & Checker" : "semua group");
        return "audit/index";
    }
}
