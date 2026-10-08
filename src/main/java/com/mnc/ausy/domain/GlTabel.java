package com.mnc.ausy.domain;

import jakarta.persistence.*;
import java.math.BigDecimal;

@Entity
@Table(name = "gl_tabel")
public class GlTabel {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private String gltrfy;   // foreign currency
    private String gltrcd;   // D/C
    private String glprdc;   // product code
    private String gltrac;   // transaction account (GL code)
    private String gltrpd;   // posting date ddMMyy
    @Column(name="gl_name") private String glName;
    private String gltrds;   // description (<=40)
    private String gltrbr;   // branch
    @Column(name="br_name") private String brName;
    private String gltrcc;   // cost center
    private String gltrcy;   // currency
    private BigDecimal gltram;
    private String glflag;
    private String glresp;
    @Column(name="cs_name") private String csName;
    @Column(name="no_paket") private String noPaket;
    private BigDecimal gleqam;
    private BigDecimal glotam;
    private String gltrus;   // inputer
    private String gltrpd1;
    @Column(name="status_print") private String statusPrint;
    private String glsrc;
    private String glotcc;
    private String gltrrf;   // reference
    @Column(name="status_dual") private String statusDual;
    private Short active = 1;   // 1=active, 0=soft-deleted (Workflow 8.3.e)
    @Column(name="export_req_id") private Long exportReqId;   // link ke gl_export_request saat diajukan export

    // getters/setters
    public Short getActive(){return active;} public void setActive(Short v){active=v;}
    public Long getId(){return id;} public void setId(Long v){id=v;}
    public String getGltrfy(){return gltrfy;} public void setGltrfy(String v){gltrfy=v;}
    public String getGltrcd(){return gltrcd;} public void setGltrcd(String v){gltrcd=v;}
    public String getGlprdc(){return glprdc;} public void setGlprdc(String v){glprdc=v;}
    public String getGltrac(){return gltrac;} public void setGltrac(String v){gltrac=v;}
    public String getGltrpd(){return gltrpd;} public void setGltrpd(String v){gltrpd=v;}
    public String getGlName(){return glName;} public void setGlName(String v){glName=v;}
    public String getGltrds(){return gltrds;} public void setGltrds(String v){gltrds=v;}
    public String getGltrbr(){return gltrbr;} public void setGltrbr(String v){gltrbr=v;}
    public String getBrName(){return brName;} public void setBrName(String v){brName=v;}
    public String getGltrcc(){return gltrcc;} public void setGltrcc(String v){gltrcc=v;}
    public String getGltrcy(){return gltrcy;} public void setGltrcy(String v){gltrcy=v;}
    public BigDecimal getGltram(){return gltram;} public void setGltram(BigDecimal v){gltram=v;}
    public String getGlflag(){return glflag;} public void setGlflag(String v){glflag=v;}
    public String getGlresp(){return glresp;} public void setGlresp(String v){glresp=v;}
    public String getCsName(){return csName;} public void setCsName(String v){csName=v;}
    public String getNoPaket(){return noPaket;} public void setNoPaket(String v){noPaket=v;}
    public BigDecimal getGleqam(){return gleqam;} public void setGleqam(BigDecimal v){gleqam=v;}
    public BigDecimal getGlotam(){return glotam;} public void setGlotam(BigDecimal v){glotam=v;}
    public String getGltrus(){return gltrus;} public void setGltrus(String v){gltrus=v;}
    public String getGltrpd1(){return gltrpd1;} public void setGltrpd1(String v){gltrpd1=v;}
    public String getStatusPrint(){return statusPrint;} public void setStatusPrint(String v){statusPrint=v;}
    public String getGlsrc(){return glsrc;} public void setGlsrc(String v){glsrc=v;}
    public String getGlotcc(){return glotcc;} public void setGlotcc(String v){glotcc=v;}
    public String getGltrrf(){return gltrrf;} public void setGltrrf(String v){gltrrf=v;}
    public String getStatusDual(){return statusDual;} public void setStatusDual(String v){statusDual=v;}
    public Long getExportReqId(){return exportReqId;} public void setExportReqId(Long v){exportReqId=v;}

    /**
     * Kunci batch seperti rekap {@code approvedPakets()}: data tambahan manual (atau tanpa nomor
     * batch) dikelompokkan sebagai {@code (manual)} (BUG_AUSY_00126). Dipakai unduhan per batch
     * (REQ_AUSY_00023, 00029) supaya isinya sama dengan daftar batch yang ditawarkan.
     */
    public String batchKey() {
        boolean manual = "m".equals(glsrc) || noPaket == null || noPaket.isBlank();
        return manual ? "(manual)" : noPaket;
    }
}
