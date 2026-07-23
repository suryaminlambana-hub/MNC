package com.mnc.ausy.web;

import com.mnc.ausy.domain.TlogTransaction;
import com.mnc.ausy.repo.TlogActivityRepository;
import com.mnc.ausy.repo.TlogTransactionRepository;
import com.mnc.ausy.security.AusyUserDetails;
import com.mnc.ausy.service.ExcelExportService;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

import java.time.format.DateTimeFormatter;
import java.util.*;

/** Immutable audit-trail viewer (read-only) — PDF §7.3 / §8 / §9. */
@Controller
public class AuditController {
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("dd-MM-yyyy HH:mm:ss");
    private final TlogTransactionRepository tx;
    private final TlogActivityRepository act;
    private final ExcelExportService excel;

    public AuditController(TlogTransactionRepository tx, TlogActivityRepository act, ExcelExportService excel) {
        this.tx = tx; this.act = act; this.excel = excel;
    }

    @GetMapping("/audit/export")
    public void export(@AuthenticationPrincipal AusyUserDetails me, HttpServletResponse resp) throws Exception {
        List<TlogTransaction> rows = tx.findAllByOrderByTimeActivityDesc(PageRequest.of(0, 5000));
        List<String> head = List.of("Waktu","User","Aksi","Detail");
        List<List<Object>> data = new ArrayList<>();
        for (TlogTransaction t : rows) {
            data.add(Arrays.asList(t.getTimeActivity() == null ? "" : t.getTimeActivity().format(TS),
                    t.getUserId(), t.getNameActivity(), t.getTinfo()));
        }
        excel.download(resp, "Audit Trail", "Audit_Trail", head, data, false);
    }

    @GetMapping("/audit")
    public String audit(Model model) {
        model.addAttribute("transactions", tx.findAllByOrderByTimeActivityDesc(PageRequest.of(0, 100)));
        model.addAttribute("activities", act.findAllByOrderByTimeActivityDesc(PageRequest.of(0, 100)));
        return "audit/index";
    }
}
