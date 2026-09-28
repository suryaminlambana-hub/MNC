package com.mnc.ausy.service;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Karakter yang boleh masuk ke data GL (BUG_AUSY_00162).
 *
 * ACCTXT adalah berkas teks lebar tetap yang dibaca core banking. Simbol seperti
 * {@code @ # $ % ^ & * ~ | < >} membuat isinya salah baca, dan karakter non-ASCII
 * (huruf beraksen, tanda kutip "pintar" hasil salin dari Word, emoji) memakai lebih dari
 * satu byte sehingga menggeser posisi kolom — seluruh baris setelahnya ikut melenceng.
 * Karena itu penyaringannya di depan, saat maker mengunggah / mengisi, bukan saat file
 * dibuat: kalau ketahuan waktu generate, datanya sudah lewat otorisasi checker.
 *
 * <p><b>Bukan hanya huruf dan angka.</b> Permintaan aslinya berbunyi "hanya alfanumerik
 * serta pemisah yang diizinkan (misal spasi)", tapi data MNC yang sesungguhnya memakai
 * titik, strip dan koma — di contoh jurnal resminya ada {@code BBN GEDUNG-PERAWATAN} dan
 * {@code SRV FIN 6 7 8 3.111.}. Aturan huruf+angka+spasi saja akan menolak data MNC
 * sendiri, jadi tanda baca yang memang dipakai ikut diizinkan. Daftarnya ada di
 * {@link #ALLOWED_PUNCT} — satu tempat, gampang diketatkan kalau memang diminta.
 */
public final class GlTextRule {

    private GlTextRule() {}

    /** Tanda baca yang ikut diizinkan selain huruf, angka dan spasi. */
    public static final String ALLOWED_PUNCT = ".,-/()_:'";

    /** Ditampilkan ke user supaya jelas apa yang boleh, bukan cuma "ada karakter salah". */
    public static final String ALLOWED_TEXT =
            "huruf, angka, spasi, dan tanda baca " + ALLOWED_PUNCT;

    private static boolean ok(char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')
                || c == ' ' || ALLOWED_PUNCT.indexOf(c) >= 0;
    }

    /**
     * Karakter terlarang yang ada di {@code s}, tanpa duplikat dan urut kemunculan.
     * Kosong = teksnya aman. Null / kosong dianggap aman — kolom kosong diurus aturan lain.
     */
    public static Set<Character> offending(String s) {
        Set<Character> bad = new LinkedHashSet<>();
        if (s == null) return bad;
        for (char c : s.toCharArray()) if (!ok(c)) bad.add(c);
        return bad;
    }

    /** Ada karakter terlarang? */
    public static boolean hasOffending(String s) { return !offending(s).isEmpty(); }

    /**
     * Pesan siap tampil untuk satu kolom, atau null kalau aman.
     * Karakter kontrol ditulis sebagai kode supaya tidak jadi baris kosong di layar.
     */
    public static String problem(String label, String value) {
        Set<Character> bad = offending(value);
        if (bad.isEmpty()) return null;
        StringBuilder sb = new StringBuilder();
        for (char c : bad) {
            if (!sb.isEmpty()) sb.append(' ');
            sb.append(c < 32 || c == 127 ? "[kode " + (int) c + "]" : String.valueOf(c));
        }
        return label + " memuat karakter yang tidak diizinkan: " + sb
                + ". Hanya boleh " + ALLOWED_TEXT + ".";
    }
}
