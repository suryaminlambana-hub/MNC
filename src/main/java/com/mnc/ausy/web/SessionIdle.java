package com.mnc.ausy.web;

import jakarta.servlet.http.HttpSessionEvent;
import jakarta.servlet.http.HttpSessionListener;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

import java.time.Duration;

/**
 * REQ_AUSY_00060 — login berakhir setelah {@code ausy.session-idle} (bawaan 10 menit) tanpa
 * aktivitas.
 * <p>
 * Yang menghitung idle adalah browser (layout.html): klik, scroll, ketik, dan submit mengatur
 * ulang timernya, satu menit sebelum habis muncul peringatan, dan saat habis user di-logout lalu
 * diantar ke halaman login. Aktivitas yang tidak memicu request (mengetik di form, scroll) tetap
 * dihitung karena browser memanggil {@code /session/ping} selama user aktif.
 * <p>
 * Sesi di server diberi batas idle + 1 menit sebagai pengaman — ping dikirim paling lambat 30 detik
 * setelah aktivitas, jadi sesi server tidak pernah habis lebih dulu daripada timer browser, dan
 * tetap habis sendiri bila JavaScript tidak jalan.
 */
@Controller
public class SessionIdle implements HttpSessionListener {

    private final Duration idle;

    public SessionIdle(@Value("${ausy.session-idle:10m}") Duration idle) {
        this.idle = idle;
    }

    /** Lama idle dalam detik, untuk timer di browser. */
    public long idleSeconds() {
        return idle.toSeconds();
    }

    @Override
    public void sessionCreated(HttpSessionEvent se) {
        se.getSession().setMaxInactiveInterval((int) idle.toSeconds() + 60);
    }

    /** Dipanggil browser selama user aktif, supaya sesi di server ikut diperpanjang. */
    @GetMapping("/session/ping")
    public ResponseEntity<Void> ping() {
        return ResponseEntity.noContent().build();
    }
}
