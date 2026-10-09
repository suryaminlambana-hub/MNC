package com.mnc.ausy.service;

import org.springframework.stereotype.Component;

/**
 * REQ_AUSY_00031: nomor batch yang <b>tampil</b> ke pengguna mulai dari 1 setiap hari.
 * <p>
 * Di database {@code no_paket} tetap unik ({@code yyyyMMdd} + urut 3 digit, mis. {@code 20261009001})
 * karena dipakai sebagai kunci dan di GLTRRF. Yang berubah hanya tampilannya: {@code 20261009001}
 * tampil sebagai {@code 1 (09-10-2026)}. Nomor batch format lama (sebelum REQ_AUSY_00031) tampil apa adanya.
 * <p>
 * Dipakai di template sebagai {@code ${@batchLabel.of(x)}}.
 */
@Component("batchLabel")
public class BatchLabel {

    public static String label(String noPaket) {
        if (noPaket == null) return "";
        String p = noPaket.trim();
        if (p.length() != 11 || !p.chars().allMatch(Character::isDigit)) return p;
        int urut = Integer.parseInt(p.substring(8));
        if (urut <= 0) return p;
        return urut + " (" + p.substring(6, 8) + "-" + p.substring(4, 6) + "-" + p.substring(0, 4) + ")";
    }

    /** Untuk template Thymeleaf. */
    public String of(Object noPaket) {
        return noPaket == null ? "" : label(noPaket.toString());
    }
}
