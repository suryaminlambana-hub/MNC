package com.mnc.ausy.service;

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
 * <h2>Dibersihkan, bukan ditolak (BUG_AUSY_00180)</h2>
 *
 * Aturannya kini: <b>huruf, angka, spasi, serta titik, koma dan garis bawah</b>. Selain itu
 * <b>diganti spasi</b>, lalu spasi di kedua ujung dibuang.
 *
 * Sebelumnya karakter asing membuat seluruh unggahan DITOLAK. Itu tidak bisa dipertahankan:
 * daftar referensi MNC sendiri penuh tanda baca — dari 1.774 nama pada sheet COA, 1.279 memuat
 * karakter di luar huruf/angka/spasi, dan di Branch_Code 78 dari 86. Nama-nama itu masuk ke
 * Sheet1 lewat VLOOKUP tanpa diketik siapa pun, sehingga maker yang mengisi template dengan
 * benar tetap ditolak pada sebagian besar akun GL. Membersihkan membuat data tetap bisa
 * diproses, dan berkas ACCTXT tetap aman karena yang tersisa hanya karakter yang dikenal.
 *
 * <p>Urutannya disengaja, sesuai permintaan: ganti dulu, baru trim. Dengan begitu karakter
 * asing di ujung tidak meninggalkan spasi menggantung — {@code "&KAS BESAR&"} menjadi
 * {@code "KAS BESAR"}, bukan {@code " KAS BESAR "}.
 */
public final class GlTextRule {

    private GlTextRule() {}

    /**
     * Tanda baca yang ikut diizinkan selain huruf, angka dan spasi (BUG_AUSY_00180).
     * Tempat satu-satunya kalau daftar ini perlu diubah lagi.
     */
    public static final String ALLOWED_PUNCT = ".,_";

    /** Ditampilkan ke user supaya jelas apa yang dipertahankan, bukan cuma "ada yang diganti". */
    public static final String ALLOWED_TEXT = "huruf, angka, spasi, titik, koma, dan garis bawah";

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
     * Rapikan sebuah isian: karakter di luar yang diizinkan diganti spasi, lalu kedua ujungnya
     * dibuang (BUG_AUSY_00180).
     *
     * Null tetap null, dan teks yang sudah bersih dikembalikan apa adanya — pemanggil bisa
     * membandingkan hasilnya dengan masukan untuk tahu ada yang berubah atau tidak.
     */
    public static String clean(String s) {
        if (s == null) return null;
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            sb.append(ok(c) ? c : ' ');
        }
        return trim(sb.toString());
    }

    /** Apakah {@link #clean(String)} akan mengubah isinya. */
    public static boolean needsClean(String s) {
        return s != null && !s.equals(clean(s));
    }
}
