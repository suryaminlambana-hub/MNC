package com.mnc.ausy.config;

import com.mnc.ausy.security.AusyUserDetails;
import com.mnc.ausy.service.AccessService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.authorization.AuthorizationManager;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.RequestAuthorizationContext;

@Configuration
public class SecurityConfig {

    private final AccessService access;

    public SecurityConfig(AccessService access) { this.access = access; }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();   // accepts legacy $2y$ hashes from the PHP app
    }

    /**
     * Izin per-request dibaca dari data (Access Menu), bukan dari daftar group id di kode.
     * Path yang tidak dimiliki item akses mana pun (dashboard, ganti password) tetap terbuka
     * untuk semua yang sudah login — sama seperti aturan anyRequest().authenticated() dulu.
     */
    private AuthorizationManager<RequestAuthorizationContext> menuAccess() {
        return (auth, ctx) -> {
            Authentication a = auth.get();
            if (a == null || !a.isAuthenticated() || a instanceof AnonymousAuthenticationToken)
                return new AuthorizationDecision(false);
            HttpServletRequest req = ctx.getRequest();
            String path = req.getRequestURI();
            String cp = req.getContextPath();
            if (cp != null && !cp.isEmpty() && path.startsWith(cp)) path = path.substring(cp.length());
            // Hak akses melekat pada user, bukan role (BUG_AUSY_00143).
            String userId = (a.getPrincipal() instanceof AusyUserDetails u) ? u.getUsername() : null;
            return new AuthorizationDecision(access.allowedUser(userId, path));
        };
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/login", "/webjars/**", "/css/**", "/js/**", "/img/**", "/favicon.ico").permitAll()
                // Segregation of Duties tidak lagi di-hardcode per group id: hak akses dibaca dari
                // group_menu (Access Menu) lewat AccessService, supaya group buatan admin bisa
                // membuka menu yang diberikan padanya (BUG_AUSY_00113) dan admin yang mengaturnya.
                // Hak bawaan 4 group lama diseed di V18 sehingga perilakunya tidak berubah.
                .anyRequest().access(menuAccess())
            )
            .formLogin(form -> form
                .loginPage("/login")
                .loginProcessingUrl("/login")
                .usernameParameter("username")
                .passwordParameter("password")
                .defaultSuccessUrl("/dashboard", true)
                .failureUrl("/login?error")
                .permitAll()
            )
            .logout(logout -> logout
                .logoutUrl("/logout")
                .logoutSuccessUrl("/login?logout")
                .permitAll()
            )
            // Never show the whitelabel 403 (e.g. a stale CSRF token from a cached tab):
            // bounce anonymous users to a fresh login, authenticated users back to the dashboard.
            .exceptionHandling(ex -> ex.accessDeniedHandler((req, res, e) -> {
                boolean anon = req.getUserPrincipal() == null;
                res.sendRedirect(req.getContextPath() + (anon ? "/login?expired" : "/dashboard?denied"));
            }));
        return http.build();
    }
}
