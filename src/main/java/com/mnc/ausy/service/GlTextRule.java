package com.mnc.ausy.service;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Karakter yang boleh masuk ke data GL.
 *
 * ACCTXT adalah berkas teks lebar tetap yang dibaca core banking. Simbol seperti
 * {@code @ # $ % ^ & * ~ | < >} membuat isinya salah baca, dan karakter non-ASCII
 * (huruf beraksen, tanda kutip "pintar" hasil salin dari Word, emoji) memakai lebih dari
 * satu byte sehingga menggeser posisi kolom — seluruh baris setelahnya ikut melenceng.
 * Karena itu penyaringannya di depan, saat maker mengunggah / mengisi, bukan saat file
 * dibuat: kalau ketahuan waktu generate, datanya sudah lewat otorisasi checker.
 *
 * <h2>Daftar yang diizinkan, dan apa yang terjadi di luar itu (BUG_AUSY_00180)</h2>
 *
 * Yang boleh: <b>huruf, angka, spasi, serta titik, koma, strip dan garis bawah</b>
 * ({@code . , - _}). Yang ditegaskan tidak boleh: {@code / ( ) ; ' " &} — dan selain itu.
 *
 * Isian yang memuatnya <b>DITOLAK</b>, bukan dibersihkan diam-diam. Keputusan pengguna: data
 * yang tidak memenuhi aturan harus ketahuan dan diperbaiki di sumbernya, bukan diubah sendiri
 * oleh aplikasi lalu disetujui Checker dalam bentuk yang berbeda dari yang diajukan.
 *
 * <p><b>Spasi di ujung dibuang lebih dulu, baru diperiksa.</b> Urutan itu penting: {@code \n}
 * {@code \r} {@code \t} {@code \f} dan NBSP yang menempel di ujung bukan isi datanya — kalau
 * ikut diperiksa, isian yang sebenarnya sah ditolak hanya karena satu baris baru sisa
 * salin-tempel dari Excel.
 */
public final class GlTextRule {

    private GlTextRule() {}

    /**
     * Tanda baca yang ikut diizinkan selain huruf, angka dan spasi (BUG_AUSY_00180).
     * Tempat satu-satunya kalau daftar ini perlu diubah lagi.
     */
    public static final String ALLOWED_PUNCT = ".,-_";

    /** Ditampilkan ke user supaya jelas apa yang dipertahankan, bukan cuma "ada yang diganti". */
    public static final String ALLOWED_TEXT =
            "huruf, angka, spasi, titik, koma, strip, dan garis bawah";

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
     * nol-lebar.
     *
     * {@link String#trim()} sendiri tidak cukup: ia hanya mengenali karakter sampai U+0020,
     * sedangkan NBSP justru yang paling sering ikut tersalin dari Excel.
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
     * Kosong = teksnya sah. Null / kosong dianggap sah — kolom kosong diurus aturan lain.
     *
     * Diperiksa setelah {@link #trim(String)}: yang dipersoalkan hanya karakter yang benar-benar
     * bagian dari isi, bukan spasi yang menempel di ujung.
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
     * Pesan siap tampil untuk satu kolom, atau null kalau isinya sah.
     *
     * Karakter yang bermasalah disebut satu per satu supaya yang memperbaiki tahu persis apa
     * yang harus dicari. Karakter kontrol ditulis sebagai kode, bukan dicetak apa adanya —
     * kalau tidak ia hanya jadi baris kosong di layar.
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
