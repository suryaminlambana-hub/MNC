package com.mnc.ausy.service;

import com.mnc.ausy.domain.GlTabel;
import java.math.BigDecimal;

/** Byte-exact port of the PHP fixed-width GLTRRF formatters (function.php). */
public final class GltrrfFormatter {
    private GltrrfFormatter() {}

    /** Trim + keep only printable ASCII (0x20..0x7E); non-ASCII/control -> space (AS400-safe). */
    private static String s(Object o) {
        if (o == null) return "";
        String v = o.toString().trim();
        StringBuilder sb = new StringBuilder(v.length());
        for (int i = 0; i < v.length(); i++) { char c = v.charAt(i); sb.append(c >= 0x20 && c <= 0x7E ? c : ' '); }
        return sb.toString();
    }
    /**
     * Kolom numerik rata kanan: diisi nol di depan sampai n karakter, dan kalau kepanjangan
     * dipotong dari depan (n karakter terakhir yang dipertahankan). Dulu nilai kepanjangan
     * dibiarkan apa adanya sehingga panjang baris berubah-ubah dan seluruh kolom sesudahnya
     * bergeser — persis kegagalan parsing yang dilaporkan di BUG_AUSY_00121.
     */
    private static String padL0(String v, int n) {
        v = v == null ? "" : v;
        if (v.length() > n) return v.substring(v.length() - n);
        return v.length() == n ? v : "0".repeat(n - v.length()) + v;
    }
    private static String padR (String v, int n) { v = v == null ? "" : v; return v.length() >= n ? v : v + " ".repeat(n - v.length()); }
    /** Fixed-width field: sanitize, truncate to n, right-pad with spaces to exactly n (guarantees record length). */
    private static String fit(String v, int n) { String x = s(v); if (x.length() > n) x = x.substring(0, n); return padR(x, n); }

    static String gltrbr(String v){ return padL0(s(v),5); }
    /**
     * 19 karakter. Source asli menulis {@code "00000000000" + nilai} — 11 nol DI DEPAN nilai,
     * bukan dipadding sampai 11 seperti port lama; dengan nomor akun 8 digit hasilnya 19 karakter
     * dan itulah yang ada di file ACCTXT acuan. Di sini dipadding tetap 19 supaya panjang record
     * tidak berubah kalau nomor akunnya lebih pendek (BUG_AUSY_00121).
     */
    static String gltrac(String v){ return padL0(s(v),19); }
    static String gltrcc(String v){ return padL0(s(v),5); }
    static String glresp(String v){ return padL0(s(v),5); }
    static String gltrfy(String v){ String k=s(v).replace(" ","").replace("NULL",""); return (k.equals("IDR")||k.isEmpty())?"     ":fit(" "+k+" ",5); }
    /**
     * Nominal ditulis tanpa titik desimal — 28.045.688,00 menjadi 2804568800 (2 desimal implisit),
     * sama seperti PHP yang membuang karakter titik dari nilai kolom NUMERIC(17,2). Versi Java
     * sebelumnya memakai toBigInteger() sehingga desimalnya hilang dan nominal yang dikirim ke core
     * 100 kali lebih kecil (BUG_AUSY_00121).
     */
    static String gltram(BigDecimal v){
        java.math.BigDecimal x = v == null ? java.math.BigDecimal.ZERO : v;
        return padL0(x.setScale(2, java.math.RoundingMode.HALF_UP).unscaledValue().toString(), 17);
    }
    static String gltrds(String v){ String x=s(v); if(x.length()>40)x=x.substring(0,40); return padR(x,40); }
    /** 20 karakter, tepat setelah deskripsi — sama seperti source asli. */
    static String gltrrf(String v){ return padR(fit(v,20),20); }
    static String gltrus(String v){ String x=s(v); if(x.length()>10)x=x.substring(0,10); return padR(x,10); }
    static String glflag(String v){ String x=s(v); return x.isEmpty()?" ":fit(x,1); }
    static String glrate(){ return "0000000000000"; }
    static String sisaTxt(){ return "0000000000000000000" + " ".repeat(40); }
    /** Product code 5 karakter — dulu tidak dipadding/dipotong sama sekali (sumber baris 250 char). */
    static String glprdc(String v){ String x=s(v); return padL0(x.isEmpty()?"0":x, 5); }
    // Fields the PHP left un-padded — now fixed-width so a null/short value can't shift the record.
    static String gltrcy(String v){ return fit(v,3); }   // currency (IDR)
    static String gltrcd(String v){ return fit(v,1); }   // D / C
    static String gltrpd(String v){ return fit(v,6); }   // posting date ddMMyy
    static String glsrc (String v){ return fit(v,1); }   // source p / m
    static String glotcc(String v){ return fit(v,3); }   // offset currency

    /**
     * Satu baris ACCTXT 253 karakter, dicocokkan dengan source asli MNC + file acuan dari core
     * (BUG_AUSY_00121):
     *
     * <pre>
     *   1-5    gltrbr  cabang               122-131  gltrus  operator
     *   6-24   gltrac  nomor akun (19)      132      glflag
     *  25-29   gltrcc  cost centre          133-137  glresp
     *  30-32   gltrcy  mata uang            138-154  gleqam
     *  33-37   gltrfy  kurs asing           155-167  glrate (konstan)
     *  38      gltrcd  D / C                168      glsrc
     *  39-44   gltrpd  tgl posting ddMMyy   169-171  glotcc
     *  45-61   gltram  nominal (2 desimal   172      spasi
     *                  implisit)            173-189  glotam
     *  62-101  gltrds  deskripsi (40)       190-194  glprdc
     * 102-121  gltrrf  referensi (20)       195-253  sisaTxt (19 nol + 40 spasi)
     * </pre>
     *
     * Yang salah di port lama: nomor akun dipadding 11 (harusnya 11 nol + nilai = 19) sehingga
     * seluruh baris kurang 8 karakter, dan nominal dipotong desimalnya sehingga nilainya 100 kali
     * lebih kecil. Susunan kolomnya sendiri sudah benar sejak awal.
     */
    public static String line(GlTabel r) {
        StringBuilder b = new StringBuilder();
        b.append(gltrbr(r.getGltrbr())).append(gltrac(r.getGltrac())).append(gltrcc(r.getGltrcc()));
        b.append(gltrcy(r.getGltrcy())).append(gltrfy(r.getGltrfy())).append(gltrcd(r.getGltrcd())).append(gltrpd(r.getGltrpd()));
        b.append(gltram(r.getGltram())).append(gltrds(r.getGltrds())).append(gltrrf(r.getGltrrf()));
        b.append(gltrus(r.getGltrus())).append(glflag(r.getGlflag())).append(glresp(r.getGlresp())).append(gltram(r.getGleqam()));
        b.append(glrate()).append(glsrc(r.getGlsrc())).append(glotcc(r.getGlotcc())).append(' ').append(gltram(r.getGlotam())).append(glprdc(r.getGlprdc()));
        b.append(sisaTxt());
        return b.toString();
    }
}
