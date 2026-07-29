package com.mnc.ausy.web;

import com.mnc.ausy.repo.*;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.core.Authentication;
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
    private final TUploadRepository uploads;

    public DashboardController(UserRepository users, GroupUserRepository groups, GlTabelRepository gl,
                               GlChangeRequestRepository cr, TlogTransactionRepository tx,
                               TUploadRepository uploads) {
        this.users = users; this.groups = groups; this.gl = gl; this.cr = cr; this.tx = tx;
        this.uploads = uploads;
    }

    @GetMapping("/")
    public String root() { return "redirect:/dashboard"; }

    @GetMapping("/dashboard")
    public String dashboard(Model model, Authentication auth) {
        // --- Maker gets a task-focused home (no audit trail) ---
        if (hasGroup(auth, "GROUP_3")) {
            model.addAttribute("activeGl", gl.countByActive((short) 1));
            model.addAttribute("glActiveList", gl.findTop200ByActiveOrderByIdDesc((short) 1));
            model.addAttribute("pendingPakets", gl.pendingPakets().size());
            model.addAttribute("pendingChanges", cr.countByStatus("PENDING"));
            model.addAttribute("pendingChangeList", cr.findByStatusOrderByCreatedAtDesc("PENDING"));
            model.addAttribute("uploadHistory", uploads.findTop50ByOrderByAdddtDesc());
            return "dashboard-maker";
        }

        // --- Checker (Approver): only what needs authorizing + audit-trail recent activity ---
        if (hasGroup(auth, "GROUP_4")) {
            java.util.List<Object[]> pendingPaketList = gl.pendingPakets();
            model.addAttribute("pendingPaketList", pendingPaketList);
            model.addAttribute("pendingPakets", pendingPaketList.size());
            model.addAttribute("pendingChanges", cr.countByStatus("PENDING"));
            model.addAttribute("pendingChangeList", cr.findByStatusOrderByCreatedAtDesc("PENDING"));
            // "Aktivitas transaksi terbaru" = audit trail, scoped to Maker + Checker only
            model.addAttribute("recent",
                    tx.findByUserGroups(java.util.List.of(3, 4), PageRequest.of(0, 10)));
            return "dashboard-checker";
        }

        // --- Admin / Approver: operational overview ---
        model.addAttribute("totalUsers", users.count());
        model.addAttribute("totalGroups", groups.count());
        model.addAttribute("activeGl", gl.countByActive((short) 1));
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

    private boolean hasGroup(Authentication auth, String authority) {
        return auth != null && auth.getAuthorities().stream()
                .anyMatch(a -> authority.equals(a.getAuthority()));
    }
}
