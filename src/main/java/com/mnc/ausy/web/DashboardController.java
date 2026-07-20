package com.mnc.ausy.web;

import com.mnc.ausy.repo.*;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class DashboardController {

    private final UserRepository users;
    private final GroupUserRepository groups;
    private final GlTabelRepository gl;
    private final GlChangeRequestRepository cr;
    private final TlogTransactionRepository tx;

    public DashboardController(UserRepository users, GroupUserRepository groups, GlTabelRepository gl,
                               GlChangeRequestRepository cr, TlogTransactionRepository tx) {
        this.users = users; this.groups = groups; this.gl = gl; this.cr = cr; this.tx = tx;
    }

    @GetMapping("/")
    public String root() { return "redirect:/dashboard"; }

    @GetMapping("/dashboard")
    public String dashboard(Model model) {
        model.addAttribute("totalUsers", users.count());
        model.addAttribute("totalGroups", groups.count());
        model.addAttribute("activeGl", gl.countByActive((short)1));
        model.addAttribute("pendingPakets", gl.pendingPakets().size());
        model.addAttribute("pendingChanges", cr.countByStatus("PENDING"));
        model.addAttribute("recent", tx.findAllByOrderByTimeActivityDesc(PageRequest.of(0, 8)));
        java.util.List<Object[]> daily = tx.dailyCounts();
        long max = 1;
        for (Object[] r : daily) max = Math.max(max, ((Number) r[1]).longValue());
        model.addAttribute("chart", daily);
        model.addAttribute("chartMax", max);
        return "dashboard";
    }
}
