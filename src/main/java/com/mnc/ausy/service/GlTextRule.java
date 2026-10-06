package com.mnc.ausy.service;

/**
 * Perapian teks data GL.
 *
 * <h2>Penyaringan simbol dicabut (BUG_AUSY_00191)</h2>
 *
 * Kelas ini dulu menolak isian yang memuat tanda baca. Aturan itu dicabut atas permintaan
 * pengguna: kolom nama dan deskripsi adalah free text yang secara bisnis memang wajar memuat
 * tanda hubung, titik, koma, tanda petik, garis miring dan tanda kurung — daftar MNC sendiri
 * penuh dengannya, dan pembatasan itu membuat data yang sah gagal disimpan.
 *
 * Yang tersisa di sini hanya perapian spasi. Pembatasan bentuk kini dipasang per kolom di
 * tempat yang memang mengenal kolomnya ({@link UploadService}): kolom kode hanya angka
 * (BUG_AUSY_00192), kolom cabang dan cost centre angka serta tanda petik satu
 * (BUG_AUSY_00193), dan currency wajib IDR (BUG_AUSY_00190).
 *
 * <p>Keamanan berkas ACCTXT tidak bergantung pada penyaringan di sini: {@link GltrrfFormatter}
 * mengubah karakter non-ASCII menjadi spasi dan memotong setiap kolom ke lebar tetapnya, jadi
 * tanda baca apa pun tidak bisa menggeser posisi kolom.
 */
public final class GlTextRule {

    private GlTextRule() {}

    /** Spasi dalam arti luas: termasuk NBSP dan spasi nol-lebar yang ikut terbawa saat salin-tempel. */
    private static boolean spasi(char c) {
        return Character.isWhitespace(c) || c == ' ' || c == '​' || c == '﻿';
    }

    /**
     * Buang spasi di depan dan di belakang — termasuk {@code \n \r \t \f}, NBSP dan spasi
     * nol-lebar.
     *
     * {@link String#trim()} sendiri tidak cukup: ia hanya mengenali karakter sampai U+0020,
     * sedangkan NBSP justru yang paling sering ikut tersalin dari Excel. Spasi semacam itu bukan
     * isi datanya, dan kalau ikut tersimpan ia terbawa ke berkas ACCTXT.
     */
    public static String trim(String s) {
        if (s == null) return null;
        int a = 0, b = s.length();
        while (a < b && spasi(s.charAt(a))) a++;
        while (b > a && spasi(s.charAt(b - 1))) b--;
        return s.substring(a, b);
    }
}
