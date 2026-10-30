package com.mnc.ausy.config;

import com.mnc.ausy.security.AusyUserDetails;
import com.mnc.ausy.security.LoginThrottle;
import com.mnc.ausy.security.LoginThrottleFilter;
import jakarta.servlet.http.HttpServletResponse;
import com.mnc.ausy.service.AccessService;
import com.mnc.ausy.service.MenuService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.context.annotation.Bean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Configuration;
import com.mnc.ausy.web.AcctxtTriggerController;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.CredentialsExpiredException;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.authorization.AuthorizationManager;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.csrf.CsrfException;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter;
import org.springframework.security.web.header.writers.StaticHeadersWriter;
import org.springframework.security.web.access.intercept.RequestAuthorizationContext;

// Hanya berlaku saat aplikasi berjalan sebagai web. Mode tugas (--ausy.task=..., REQ_AUSY_00058)
// berjalan tanpa Tomcat, sehingga bean khas web seperti HttpSecurity tidak ada dan konfigurasi
// ini akan menggagalkan start kalau tetap dimuat.
@ConditionalOnWebApplication
@Configuration
public class SecurityConfig {

    private final AccessService access;
    private final MenuService menuService;
    private final LoginThrottle throttle;

    public SecurityConfig(AccessService access, MenuService menuService, LoginThrottle throttle) {
        this.access = access;
        this.menuService = menuService;
        this.throttle = throttle;
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
                // REQ_AUSY_00058: dipanggil ausy-trigger.jar, bukan user yang login — dijaga
                // token AUSY_TRIGGER_TOKEN di controllernya sendiri.
                .requestMatchers(HttpMethod.POST, AcctxtTriggerController.PATH).permitAll()
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
                // REQ_AUSY_00019: role bermenu tunggal (EXECUTE) diantar langsung ke menunya.
                // Dashboard baginya halaman perantara yang tidak pernah berisi apa pun yang ia
                // butuhkan, dan menunya pun disembunyikan dari sidebar. Role lain tetap ke
                // Dashboard seperti biasa.
                .successHandler((req, res, auth) -> {
                    String target = "/dashboard";
                    if (auth.getPrincipal() instanceof AusyUserDetails u
                            && access.isExecuteRole(u.getIdGroup())) {
                        String first = menuService.firstMenuUrl(u.getUsername());
                        // Kalau haknya belum diisi, jatuh kembali ke Dashboard daripada
                        // mengantar ke halaman yang akan ditolak.
                        if (first != null) target = first;
                    }
                    res.sendRedirect(req.getContextPath() + target);
                })
                // Password kedaluwarsa dibedakan dari password salah (BUG_AUSY_00190): tanpa ini
                // orangnya membaca "username atau password salah" lalu mencoba berkali-kali
                // dengan password yang sebenarnya benar, dan tidak pernah tahu harus minta reset.
                // Aman dibedakan karena pemeriksaannya terjadi SETELAH passwordnya cocok.
                .failureHandler((req, res, ex) -> res.sendRedirect(req.getContextPath()
                        + (ex instanceof CredentialsExpiredException ? "/login?passexpired" : "/login?error")))
                .permitAll()
            )
            .logout(logout -> logout
                .logoutUrl("/logout")
                .logoutSuccessUrl("/login?logout")
                // Cookie sesi ikut dibuang: tanpa ini halaman "Anda telah keluar" datang membawa
                // cookie sesi yang sudah mati dan dibaca sebagai sesi kedaluwarsa (lihat di bawah).
                .deleteCookies("JSESSIONID")
                .permitAll()
            )
            // REQ_AUSY_00060: request yang membawa sesi yang sudah habis (idle) diantar ke halaman
            // login dengan pesan "sesi berakhir", bukan sekadar halaman login kosong.
            // REQ_AUSY_00072: POST dengan sesi habis / tanpa token CSRF tersimpan dijawab 403 (lihat
            // tolak403), bukan redirect — Spring Security juga memakai strategi ini untuk token CSRF
            // yang hilang dari sesi. GET tetap diantar ke login seperti biasa.
            .sessionManagement(s -> s.invalidSessionStrategy((req, res) -> {
                req.getSession();
                String tujuan = req.getContextPath() + "/login?expired";
                if ("POST".equals(req.getMethod())) tolak403(res, tujuan);
                else res.sendRedirect(tujuan);
            }))
            // Pemanggil JAR tidak punya sesi/form, jadi tidak membawa token CSRF; penjaganya token pemicu.
            .csrf(c -> c.ignoringRequestMatchers(AcctxtTriggerController.PATH))
            // REQ_AUSY_00070: pembatasan percobaan login, sebelum kredensial diperiksa.
            .addFilterBefore(new LoginThrottleFilter(throttle), UsernamePasswordAuthenticationFilter.class)
            // REQ_AUSY_00069: header keamanan tambahan. X-Frame-Options DENY, X-Content-Type-Options
            // dan Cache-Control sudah bawaan Spring Security; HSTS ikut terkirim bila request HTTPS.
            .headers(h -> h
                .addHeaderWriter(new StaticHeadersWriter("Permissions-Policy",
                        "camera=(), microphone=(), geolocation=(), payment=(), usb=(), interest-cohort=()"))
                .referrerPolicy(r -> r.policy(ReferrerPolicyHeaderWriter.ReferrerPolicy.STRICT_ORIGIN_WHEN_CROSS_ORIGIN)))
            .exceptionHandling(ex -> ex.accessDeniedHandler((req, res, e) -> {
                boolean anon = req.getUserPrincipal() == null;
                String tujuan = req.getContextPath() + (anon ? "/login?expired" : "/dashboard?denied");
                // REQ_AUSY_00072: request tanpa token CSRF yang sah dijawab 403 apa adanya. Dulu
                // dijawab redirect, yang di mata pemindai berakhir "200 tanpa token CSRF". Browser
                // tetap diantar ke halaman yang sama (tab lama dengan token basi, sesi habis).
                if (e instanceof CsrfException) { tolak403(res, tujuan); return; }
                // Selain itu tidak pernah whitelabel 403: anonim ke login, yang sudah login ke dashboard.
                res.sendRedirect(tujuan);
            }));
        return http.build();
    }

    /**
     * 403 dengan halaman kecil yang langsung mengantar browser ke {@code tujuan} (REQ_AUSY_00072):
     * status ditolak terlihat jelas bagi pemindai, sedangkan user tetap sampai di halaman yang sama
     * seperti dulu (login "sesi berakhir" / dashboard).
     */
    private static void tolak403(HttpServletResponse res, String tujuan) throws java.io.IOException {
        res.setStatus(HttpServletResponse.SC_FORBIDDEN);
        res.setContentType("text/html;charset=UTF-8");
        res.getWriter().write("<!doctype html><meta charset=utf-8><meta http-equiv=refresh content=\"0;url="
                + tujuan + "\"><title>Sesi berakhir</title><p>Halaman sudah kedaluwarsa."
                + " <a href=\"" + tujuan + "\">Lanjutkan</a></p>");
    }
}
