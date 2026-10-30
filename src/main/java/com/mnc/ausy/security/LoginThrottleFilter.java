package com.mnc.ausy.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Duration;

/**
 * Menahan POST /login dari username atau IP yang sedang dikunci {@link LoginThrottle}
 * (REQ_AUSY_00070). Jawabannya HTTP 429 + {@code Retry-After}, lalu browser diantar ke halaman
 * login dengan pesan berapa menit lagi boleh mencoba.
 */
public class LoginThrottleFilter extends OncePerRequestFilter {
    private final LoginThrottle throttle;

    public LoginThrottleFilter(LoginThrottle throttle) { this.throttle = throttle; }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest req) {
        String path = req.getRequestURI().substring(req.getContextPath().length());
        return !("POST".equals(req.getMethod()) && "/login".equals(path));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        Duration sisa = throttle.sisaKunci(req.getParameter("username"), req.getRemoteAddr());
        if (sisa.isZero()) { chain.doFilter(req, res); return; }
        long menit = Math.max(1, (sisa.getSeconds() + 59) / 60);
        String tujuan = req.getContextPath() + "/login?locked=" + menit;
        res.setStatus(429);
        res.setHeader("Retry-After", String.valueOf(sisa.getSeconds()));
        res.setContentType("text/html;charset=UTF-8");
        res.getWriter().write("<!doctype html><meta charset=utf-8><meta http-equiv=refresh content=\"0;url="
                + tujuan + "\"><title>Terlalu banyak percobaan</title><p>Terlalu banyak percobaan login."
                + " Coba lagi dalam " + menit + " menit. <a href=\"" + tujuan + "\">Kembali ke halaman login</a></p>");
    }
}
