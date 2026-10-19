package com.mnc.ausy.web;

import com.mnc.ausy.security.AusyUserDetails;
import com.mnc.ausy.service.AccountPasswordService;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Ganti password sendiri dari pop-up di header (REQ_AUSY_00064). Alamatnya tidak dimiliki item
 * menu mana pun, jadi terbuka untuk semua user yang sudah login — tanpa itu role mana pun yang
 * tidak diberi menu khusus tidak akan bisa mengganti password.
 */
@RestController
public class AccountController {

    /** Salah password lama berturut-turut dalam satu sesi sebelum sesinya diakhiri. */
    private static final int BATAS_SALAH = 5;
    private static final String SALAH = "ausy.pw.salah";

    private final AccountPasswordService service;

    public AccountController(AccountPasswordService service) { this.service = service; }

    @PostMapping("/account/password")
    public ResponseEntity<Map<String, Object>> ganti(@AuthenticationPrincipal AusyUserDetails me,
                                                      @RequestParam String lama, @RequestParam String baru,
                                                      @RequestParam String konfirmasi,
                                                      HttpServletRequest req, HttpServletResponse res) {
        if (me == null) return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("ok", false, "pesan", "Sesi berakhir."));
        AccountPasswordService.Hasil h = service.ganti(me.getUsername(), lama, baru, konfirmasi);
        HttpSession s = req.getSession(false);
        if (h.ok()) {
            // Sesi lama diakhiri: password berubah, dan login ulang membuktikan password barunya.
            if (s != null) s.invalidate();
            buangCookie(req, res);
            return ResponseEntity.ok(Map.of("ok", true, "pesan", h.pesan(), "logout", true));
        }
        // Sesi yang terus menebak password lama diakhiri, supaya sesi curian tidak bisa dipakai menebak.
        if (s != null && "Password lama salah.".equals(h.pesan())) {
            int n = s.getAttribute(SALAH) instanceof Integer i ? i + 1 : 1;
            s.setAttribute(SALAH, n);
            if (n >= BATAS_SALAH) {
                s.invalidate();
                buangCookie(req, res);
                return ResponseEntity.ok(Map.of("ok", false, "logout", true,
                        "pesan", "Password lama salah " + BATAS_SALAH + " kali. Demi keamanan, silakan login kembali."));
            }
        }
        return ResponseEntity.ok(Map.of("ok", false, "pesan", h.pesan()));
    }

    /**
     * Cookie sesi ikut dibuang. Tanpa itu halaman login datang membawa cookie sesi yang sudah mati
     * dan dibaca sebagai "sesi berakhir" (invalidSessionUrl) — sama seperti pada logout biasa.
     */
    private static void buangCookie(HttpServletRequest req, HttpServletResponse res) {
        Cookie c = new Cookie("JSESSIONID", "");
        c.setMaxAge(0);
        c.setPath(req.getContextPath().isEmpty() ? "/" : req.getContextPath());
        res.addCookie(c);
    }
}
