package com.mnc.ausy.web;

import com.mnc.ausy.repo.TlogActivityRepository;
import com.mnc.ausy.repo.TlogTransactionRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

/** Immutable audit-trail viewer (read-only) — PDF §7.3 / §8 / §9. */
@Controller
public class AuditController {
    private final TlogTransactionRepository tx;
    private final TlogActivityRepository act;

    public AuditController(TlogTransactionRepository tx, TlogActivityRepository act) {
        this.tx = tx; this.act = act;
    }

    @GetMapping("/audit")
    public String audit(Model model) {
        model.addAttribute("transactions", tx.findAllByOrderByTimeActivityDesc(PageRequest.of(0, 100)));
        model.addAttribute("activities", act.findAllByOrderByTimeActivityDesc(PageRequest.of(0, 100)));
        return "audit/index";
    }
}
