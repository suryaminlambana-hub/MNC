package com.mnc.ausy.web;

import com.mnc.ausy.repo.*;
import com.mnc.ausy.security.AusyUserDetails;
import com.mnc.ausy.service.ConfigChangeService;
import com.mnc.ausy.service.GlChangeService;
import com.mnc.ausy.service.GlExportService;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

import java.util.List;

@Controller
public class DashboardController {

    private final UserRepository users;
    private final GroupUserRepository groups;
    private final GlTabelRepository gl;
    private final TlogTransactionRepository tx;
    private final GlChangeService glChange;
    private final ConfigChangeService configChange;
    private final GlExportService glExport;

    public DashboardController(UserRepository users, GroupUserRepository groups, GlTabelRepository gl,
                               TlogTransactionRepository tx, GlChangeService glChange,
                               ConfigChangeService configChange, GlExportService glExport) {
        this.users = users; this.groups = groups; this.gl = gl; this.tx = tx;
        this.glChange = glChange; this.configChange = configChange; this.glExport = glExport;
    }

    @GetMapping("/")
    public String root() { return "redirect:/dashboard"; }

    @GetMapping("/dashboard")
    public String dashboard(Model model, Authentication auth, @AuthenticationPrincipal AusyUserDetails principal) {
        // --- Admin / Config (1): only user & group config + user-change history (no GL/business) ---
        if (hasGroup(auth, "GROUP_1")) {
            model.addAttribute("totalUsers", users.count());
            model.addAttribute("totalGroups", groups.count());
            model.addAttribute("pendingUsers", configChange.countPending());
            model.addAttribute("pendingUserList", configChange.pendingList());
            model.addAttribute("userHistory", tx.byActivityPrefix("CONFIG_CHANGE%", PageRequest.of(0, 50)));
            return "dashboard-admin";
        }

        // --- Admin Approval (2): only user approvals + recent activity scoped to admin side ---
        if (hasGroup(auth, "GROUP_2")) {
            model.addAttribute("pendingUsers", configChange.countPending());
            model.addAttribute("pendingUserList", configChange.pendingList());
            model.addAttribute("recent", tx.findByUserGroups(List.of(1, 2), PageRequest.of(0, 15)));
            return "dashboard-adminapv";
        }

        // --- Maker (3): task-focused home (no audit trail) ---
        if (hasGroup(auth, "GROUP_3")) {
            model.addAttribute("activeGl", gl.countByActiveAndStatusDualNot((short) 1, "PENDING"));
            model.addAttribute("glActiveList", gl.findTop200ByActiveAndStatusDualNotOrderByIdDesc((short) 1, "PENDING"));
            // "Perubahan GL Pending" = upload paket menunggu otorisasi + change request (add/edit/delete)
            List<Object[]> pendingPaketList = gl.pendingPakets();
            List<com.mnc.ausy.domain.GlChangeRequest> pendingChangeList = glChange.pendingVisible();
            model.addAttribute("pendingPaketList", pendingPaketList);
            model.addAttribute("pendingChangeList", pendingChangeList);
            model.addAttribute("pendingChanges", pendingPaketList.size() + pendingChangeList.size());
            String me = auth.getName();
            model.addAttribute("glHistory", tx.glHistoryForMaker(me, "%maker=" + me + "%", PageRequest.of(0, 50)));
            return "dashboard-maker";
        }

        // --- Checker (4): only what needs authorizing + audit-trail recent (scoped Maker+Checker) ---
        if (hasGroup(auth, "GROUP_4")) {
            // "Paket Menunggu Otorisasi" = upload paket + export GL (keduanya di tab Otorisasi Upload/Export GL)
            List<Object[]> pendingPaketList = gl.pendingPakets();
            List<com.mnc.ausy.domain.GlExportRequest> pendingExportList = glExport.pendingExportFor(principal.getIdGroup());
            model.addAttribute("pendingPaketList", pendingPaketList);
            model.addAttribute("pendingExportList", pendingExportList);
            model.addAttribute("pendingPakets", pendingPaketList.size() + pendingExportList.size());
            model.addAttribute("pendingChanges", glChange.pendingVisibleCount());
            model.addAttribute("pendingChangeList", glChange.pendingVisible());
            model.addAttribute("recent", tx.findByUserGroups(List.of(3, 4), PageRequest.of(0, 10)));
            return "dashboard-checker";
        }

        // --- Fallback (any other authenticated role) ---
        model.addAttribute("totalUsers", users.count());
        model.addAttribute("totalGroups", groups.count());
        model.addAttribute("activeGl", gl.countByActive((short) 1));
        model.addAttribute("pendingPakets", gl.pendingPakets().size());
        model.addAttribute("pendingChanges", glChange.pendingVisibleCount());
        model.addAttribute("recent", tx.findAllByOrderByTimeActivityDesc(PageRequest.of(0, 8)));
        List<Object[]> daily = tx.dailyCounts();
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
