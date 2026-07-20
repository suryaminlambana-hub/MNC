package com.mnc.ausy.security;

import com.mnc.ausy.repo.UserRepository;
import com.mnc.ausy.service.AuditService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.context.event.EventListener;
import org.springframework.security.authentication.event.AuthenticationFailureBadCredentialsEvent;
import org.springframework.security.authentication.event.InteractiveAuthenticationSuccessEvent;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.time.LocalDateTime;

/** Records every login (success + failure) into the immutable audit trail, and stamps last_login. */
@Component
public class LoginAuditListener {
    private final AuditService audit;
    private final UserRepository users;

    public LoginAuditListener(AuditService audit, UserRepository users) {
        this.audit = audit; this.users = users;
    }

    private static String ip() {
        try {
            ServletRequestAttributes a = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
            HttpServletRequest r = a == null ? null : a.getRequest();
            return r == null ? "-" : r.getRemoteAddr();
        } catch (Exception e) { return "-"; }
    }

    @EventListener
    public void onSuccess(InteractiveAuthenticationSuccessEvent ev) {
        String user = ev.getAuthentication().getName();
        audit.activity(user, ip(), "LOGIN", "Login berhasil");
        users.findByUserId(user).ifPresent(u -> { u.setLastLogin(LocalDateTime.now()); users.save(u); });
    }

    @EventListener
    public void onFailure(AuthenticationFailureBadCredentialsEvent ev) {
        Object p = ev.getAuthentication().getPrincipal();
        audit.activity(p == null ? "?" : p.toString(), ip(), "LOGIN_GAGAL", "Username atau password salah");
    }
}
