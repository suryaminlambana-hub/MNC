package com.mnc.ausy.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Set;

/**
 * Hanya GET, HEAD dan POST yang diterima (REQ_AUSY_00070 — pentest: PUT/DELETE "allowed without
 * auth check"). Aplikasi tidak punya satu pun endpoint PUT/DELETE/PATCH; method lain langsung
 * dijawab 405 sebelum menyentuh filter apa pun, termasuk pada path publik (login, aset statis)
 * yang sebelumnya menjawabnya dengan halaman biasa.
 */
@ConditionalOnWebApplication
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class MetodeHttpFilter extends OncePerRequestFilter {
    static final Set<String> DIIZINKAN = Set.of("GET", "HEAD", "POST");

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        if (DIIZINKAN.contains(req.getMethod())) { chain.doFilter(req, res); return; }
        // Ditulis langsung, bukan sendError: sendError diteruskan ke /error yang dijaga login,
        // sehingga pemanggil anonim malah menerima redirect ke halaman login, bukan 405.
        res.setStatus(HttpServletResponse.SC_METHOD_NOT_ALLOWED);
        res.setHeader("Allow", "GET, HEAD, POST");
        res.setContentType("text/plain;charset=UTF-8");
        res.getWriter().write("405 Method Not Allowed");
    }
}
