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
    static String gltrac(String v){ return padL0(s(v),11); }
    static String gltrcc(String v){ return padL0(s(v),5); }
    static String glresp(String v){ return padL0(s(v),5); }
    static String gltrfy(String v){ String k=s(v).replace(" ","").replace("NULL",""); return (k.equals("IDR")||k.isEmpty())?"     ":fit(" "+k+" ",5); }
    static String gltram(BigDecimal v){ String n = v==null?"0":v.toBigInteger().toString(); return padL0(n,17); }
    static String gltrds(String v){ String x=s(v); if(x.length()>40)x=x.substring(0,40); return padR(x,40); }
    static String gltrrf(String v){ return padR(s(v),20); }
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

    /** One fixed-width line for a GL row, matching the PHP otorisasi assembly. */
    public static String line(GlTabel r) {
        StringBuilder b = new StringBuilder();
        b.append(gltrbr(r.getGltrbr())).append(gltrac(r.getGltrac())).append(gltrcc(r.getGltrcc())).append(gltrcy(r.getGltrcy()));
        b.append(gltrfy(r.getGltrfy())).append(gltrcd(r.getGltrcd())).append(gltrpd(r.getGltrpd())).append(gltram(r.getGltram())).append(gltrds(r.getGltrds()));
        b.append(gltrrf(r.getGltrrf())).append(gltrus(r.getGltrus())).append(glflag(r.getGlflag())).append(glresp(r.getGlresp())).append(gltram(r.getGleqam()));
        b.append(glrate()).append(glsrc(r.getGlsrc())).append(glotcc(r.getGlotcc())).append(' ').append(gltram(r.getGlotam())).append(glprdc(r.getGlprdc()));
        b.append(sisaTxt());
        return b.toString();
    }
}
