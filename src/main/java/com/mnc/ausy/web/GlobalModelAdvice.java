package com.mnc.ausy.web;

import com.mnc.ausy.security.AusyUserDetails;
import com.mnc.ausy.service.MenuService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

/** Injects the current user, sidebar, and active path into every view. */
@ControllerAdvice
public class GlobalModelAdvice {

    private final MenuService menuService;

    public GlobalModelAdvice(MenuService menuService) { this.menuService = menuService; }

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
