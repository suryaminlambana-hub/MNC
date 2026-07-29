package com.mnc.ausy.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
public class SecurityConfig {

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();   // accepts legacy $2y$ hashes from the PHP app
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/login", "/webjars/**", "/css/**", "/js/**", "/img/**", "/favicon.ico").permitAll()
                // --- Segregation of Duties (group authorities: GROUP_1..GROUP_4) ---
                // (1) CONFIG/SETTING: user, group, menu, access, approval, GL Code (parameter)
                .requestMatchers("/users/**", "/groups/**", "/menus/**", "/access/**", "/approval/**", "/gl-code/**").hasAuthority("GROUP_1")
                // (3) BUSINESS MAKER: upload & GL data operations
                .requestMatchers("/upload/**", "/nala/**", "/gl-data/**", "/gl-delete/**").hasAuthority("GROUP_3")
                // (2) CONFIG APPROVER (Admin Approval): authorize user/config change requests
                .requestMatchers("/config-change/**").hasAuthority("GROUP_2")
                // (4) BUSINESS APPROVER: otorisasi / approve (approve-only)
                .requestMatchers("/otorisasi/**", "/gl-change/**").hasAuthority("GROUP_4")
                // Audit Trail: only the approver roles — Config Approver (2) & Checker (4).
                // Admin (1) and Maker (3) cannot view it (per role revisions).
                .requestMatchers("/audit/**").hasAnyAuthority("GROUP_2", "GROUP_4")
                // shared read (dashboard, list, rekap, report) — any authenticated role
                .anyRequest().authenticated()
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
