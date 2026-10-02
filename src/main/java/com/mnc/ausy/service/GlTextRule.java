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
 * <p><b>Hanya huruf, angka dan spasi.</b> Tanda baca apa pun ikut ditolak — titik, koma,
 * strip, garis miring, kurung. Aturan ini diminta tegas oleh pengguna (BUG_AUSY_00162)
 * sesudah alternatif yang lebih longgar ditawarkan, jadi jangan dilonggarkan lagi tanpa
 * permintaan baru.
 *
 * <p>Konsekuensinya disadari: jurnal contoh MNC sendiri memuat {@code BBN GEDUNG-PERAWATAN},
 * {@code CAB.KPNO MENARA ICB} dan {@code SRV FIN 6 7 8 3.111.}, dan data demo memakai
 * {@code BIAYA #1} — semuanya ditolak aturan ini dan harus ditulis ulang tanpa tanda baca.
 * Kalau nanti diputuskan sebagian tanda baca boleh, {@link #ALLOWED_PUNCT} tempatnya.
 */
public final class GlTextRule {

    private GlTextRule() {}

    /**
     * Tanda baca yang ikut diizinkan selain huruf, angka dan spasi.
     * Sengaja KOSONG: permintaannya hanya alfanumerik dan spasi.
     */
    public static final String ALLOWED_PUNCT = "";

    /** Ditampilkan ke user supaya jelas apa yang boleh, bukan cuma "ada karakter salah". */
    public static final String ALLOWED_TEXT = "huruf, angka, dan spasi";

    private static boolean ok(char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')
                || c == ' ' || ALLOWED_PUNCT.indexOf(c) >= 0;
    }

    /** Spasi dalam arti luas: termasuk NBSP dan spasi nol-lebar yang ikut terbawa saat salin-tempel. */
    private static boolean spasi(char c) {
        return Character.isWhitespace(c) || c == ' ' || c == '​' || c == '﻿';
    }

    /**
     * Buang spasi di depan dan di belakang — termasuk {@code \n \r \t \f}, NBSP dan spasi
     * nol-lebar (BUG_AUSY_00180).
     *
     * Dilakukan SEBELUM pemeriksaan karakter, dan hasilnya pula yang disimpan. Sebelumnya
     * pemeriksaan jalan di nilai mentah, sehingga satu baris baru atau satu spasi hasil
     * salin-tempel dari Excel membuat isian yang sebenarnya sah ditolak sebagai "karakter tidak
     * diizinkan". Spasi semacam itu bukan isi datanya — cukup dibuang, tidak perlu
     * dipersoalkan. {@link String#trim()} sendiri tidak cukup: ia hanya mengenali karakter
     * sampai U+0020, sedangkan NBSP justru yang paling sering ikut tersalin.
     */
    public static String trim(String s) {
        if (s == null) return null;
        int a = 0, b = s.length();
        while (a < b && spasi(s.charAt(a))) a++;
        while (b > a && spasi(s.charAt(b - 1))) b--;
        return s.substring(a, b);
    }

    /**
     * Karakter terlarang yang ada di {@code s}, tanpa duplikat dan urut kemunculan.
     * Kosong = teksnya aman. Null / kosong dianggap aman — kolom kosong diurus aturan lain.
     *
     * Diperiksa setelah dirapikan {@link #trim(String)}: yang dipersoalkan hanya karakter yang
     * benar-benar bagian dari isi, bukan spasi yang menempel di ujung.
     */
    public static Set<Character> offending(String s) {
        Set<Character> bad = new LinkedHashSet<>();
        if (s == null) return bad;
        for (char c : trim(s).toCharArray()) if (!ok(c)) bad.add(c);
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
