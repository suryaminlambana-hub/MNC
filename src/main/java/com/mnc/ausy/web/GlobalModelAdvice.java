package com.mnc.ausy.web;

import com.mnc.ausy.security.AusyUserDetails;
import com.mnc.ausy.service.AccessService;
import com.mnc.ausy.service.ApprovalService;
import com.mnc.ausy.service.MenuService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

/** Injects the current user, sidebar, and active path into every view. */
@ControllerAdvice
public class GlobalModelAdvice {

    private final MenuService menuService;
    private final AccessService access;
    private final ApprovalService approval;

    public GlobalModelAdvice(MenuService menuService, AccessService access, ApprovalService approval) {
        this.menuService = menuService; this.access = access; this.approval = approval;
    }

    /**
     * Group ini punya halaman pengajuan tapi belum punya Checker/Approver aktif — pengajuannya
     * pasti ditolak, jadi diberi tahu di setiap halaman (BUG_AUSY_00116).
     */
    @ModelAttribute("submitBlocked")
    public boolean submitBlocked(@AuthenticationPrincipal AusyUserDetails user) {
        if (user == null) return false;
        return access.canSubmit(user.getIdGroup()) && !approval.hasActiveApprover(user.getIdGroup());
    }

    @ModelAttribute("submitBlockedMsg")
    public String submitBlockedMsg() { return ApprovalService.NO_APPROVER; }

    /** Card dashboard yang boleh dilihat group ini — tiap card di-guard dengan ini (BUG_AUSY_00112). */
    @ModelAttribute("cards")
    public java.util.Set<String> cards(@AuthenticationPrincipal AusyUserDetails user) {
        return user == null ? java.util.Set.of() : access.cardsFor(user.getIdGroup());
    }

    @ModelAttribute("currentUser")
    public AusyUserDetails currentUser(@AuthenticationPrincipal AusyUserDetails user) {
        return user;
    }

    @ModelAttribute("sidebar")
    public Object sidebar(@AuthenticationPrincipal AusyUserDetails user) {
        return user == null ? java.util.List.of() : menuService.sidebarFor(user.getIdGroup());
    }

    /** Path inside the app (context-path stripped), for highlighting the active menu. */
    @ModelAttribute("currentPath")
    public String currentPath(HttpServletRequest req) {
        String uri = req.getRequestURI();
        String ctx = req.getContextPath();
        if (ctx != null && !ctx.isEmpty() && uri.startsWith(ctx)) uri = uri.substring(ctx.length());
        return uri;
    }
}
