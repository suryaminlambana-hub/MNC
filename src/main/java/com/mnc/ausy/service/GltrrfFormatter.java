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
    /** 16 karakter — sesuai file ACCTXT acuan dari core (dulu 11, bikin seluruh baris meleset). */
    static String gltrac(String v){ return padL0(s(v),16); }
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
    /** 49 karakter di file acuan (dulu 40). */
    static String gltrds(String v){ String x=s(v); if(x.length()>49)x=x.substring(0,49); return padR(x,49); }
    /** Kolom referensi 10 karakter, letaknya SEBELUM deskripsi (dulu 20 karakter sesudahnya). */
    static String gltrrf(String v){ return fit(v,10); }
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

    /** Pengisi 3 karakter antara cost centre dan mata uang; di file acuan selalu "000". */
    static String filler3(){ return "000"; }

    /**
     * Satu baris ACCTXT 253 karakter, mengikuti file acuan dari core (BUG_AUSY_00121):
     *
     * <pre>
     *   1-5   gltrbr   cabang                71      spasi pemisah
     *   6-21  gltrac   nomor GL (16)         73-121  gltrds  deskripsi (49)
     *  22-26  gltrcc   cost centre          122-131  gltrus  operator
     *  27-29  "000"    pengisi              132      glflag
     *  30-32  gltrcy   mata uang            133-137  glresp
     *  33-37  gltrfy   kurs asing           138-154  gleqam
     *  38     gltrcd   D / C                155-167  glrate  (konstan)
     *  39-44  gltrpd   tgl posting ddMMyy   168      glsrc
     *  45-61  gltram   nominal              169-171  glotcc
     *  62-71  gltrrf   referensi (10)       172      spasi
     *                                       173-189  glotam
     *                                       190-194  glprdc
     *                                       195-253  sisaTxt (19 nol + 40 spasi)
     * </pre>
     *
     * Bedanya dengan versi lama (245 karakter, warisan PHP): nomor GL 16 bukan 11, ada pengisi
     * "000" sebelum mata uang, dan kolom referensi 10 karakter pindah ke depan deskripsi
     * (deskripsi ikut melebar jadi 49) — dulu referensi ditulis 20 karakter setelah deskripsi.
     */
    public static String line(GlTabel r) {
        StringBuilder b = new StringBuilder();
        b.append(gltrbr(r.getGltrbr())).append(gltrac(r.getGltrac())).append(gltrcc(r.getGltrcc())).append(filler3());
        b.append(gltrcy(r.getGltrcy())).append(gltrfy(r.getGltrfy())).append(gltrcd(r.getGltrcd())).append(gltrpd(r.getGltrpd()));
        b.append(gltram(r.getGltram())).append(gltrrf(r.getGltrrf())).append(' ').append(gltrds(r.getGltrds()));
        b.append(gltrus(r.getGltrus())).append(glflag(r.getGlflag())).append(glresp(r.getGlresp())).append(gltram(r.getGleqam()));
        b.append(glrate()).append(glsrc(r.getGlsrc())).append(glotcc(r.getGlotcc())).append(' ').append(gltram(r.getGlotam())).append(glprdc(r.getGlprdc()));
        b.append(sisaTxt());
        return b.toString();
    }
}
