package com.mnc.ausy.service;

import com.mnc.ausy.domain.GlTabel;
import java.math.BigDecimal;

/** Byte-exact port of the PHP fixed-width GLTRRF formatters (function.php). */
public final class GltrrfFormatter {
    private GltrrfFormatter() {}

    private static String s(Object o) { return o == null ? "" : o.toString().trim(); }
    private static String padL0(String v, int n) { v = v == null ? "" : v; return v.length() >= n ? v : "0".repeat(n - v.length()) + v; }
    private static String padR (String v, int n) { v = v == null ? "" : v; return v.length() >= n ? v : v + " ".repeat(n - v.length()); }

    static String gltrbr(String v){ return padL0(s(v),5); }
    static String gltrac(String v){ return padL0(s(v),11); }
    static String gltrcc(String v){ return padL0(s(v),5); }
    static String glresp(String v){ return padL0(s(v),5); }
    static String gltrfy(String v){ String k=s(v).replace(" ","").replace("NULL",""); return (k.equals("IDR")||k.isEmpty())?"     ":" "+k+" "; }
    static String gltram(BigDecimal v){ String n = v==null?"0":v.toBigInteger().toString(); return padL0(n,17); }
    static String gltrds(String v){ String x=s(v); if(x.length()>40)x=x.substring(0,40); return padR(x,40); }
    static String gltrrf(String v){ return padR(s(v),20); }
    static String gltrus(String v){ String x=s(v); if(x.length()>10)x=x.substring(0,10); return padR(x,10); }
    static String glflag(String v){ return (v==null||v.isEmpty())?" ":v; }
    static String glrate(){ return "0000000000000"; }
    static String sisaTxt(){ return "0000000000000000000" + " ".repeat(40); }
    static String glprdc(String v){ return (v==null||v.isEmpty())?"00000":v; }

    /** One fixed-width line for a GL row, matching the PHP otorisasi assembly. */
    public static String line(GlTabel r) {
        StringBuilder b = new StringBuilder();
        b.append(gltrbr(r.getGltrbr())).append(gltrac(r.getGltrac())).append(gltrcc(r.getGltrcc())).append(s(r.getGltrcy()));
        b.append(gltrfy(r.getGltrfy())).append(s(r.getGltrcd())).append(s(r.getGltrpd())).append(gltram(r.getGltram())).append(gltrds(r.getGltrds()));
        b.append(gltrrf(r.getGltrrf())).append(gltrus(r.getGltrus())).append(glflag(r.getGlflag())).append(glresp(r.getGlresp())).append(gltram(r.getGleqam()));
        b.append(glrate()).append(s(r.getGlsrc())).append(s(r.getGlotcc())).append(' ').append(gltram(r.getGlotam())).append(glprdc(r.getGlprdc()));
        b.append(sisaTxt());
        return b.toString();
    }
}
