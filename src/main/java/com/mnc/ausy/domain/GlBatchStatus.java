package com.mnc.ausy.domain;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * REQ_AUSY_00053 — status batch upload untuk kolom Status di List Data GL. Tidak ada baris berarti
 * "Menunggu Otorisasi"; lihat V49 untuk arti DIKEMBALIKAN dan REJECTED.
 */
@Entity @Table(name = "gl_batch_status")
public class GlBatchStatus {
    public static final String DIKEMBALIKAN = "DIKEMBALIKAN", REJECTED = "REJECTED";

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(name = "no_paket")    private String noPaket;
    @Column(name = "maker")       private String maker;
    @Column(name = "status")      private String status;
    @Column(name = "note")        private String note;
    @Column(name = "decided_by")  private String decidedBy;
    @Column(name = "decided_at")  private LocalDateTime decidedAt;
    @Column(name = "jml")         private Integer jml;
    @Column(name = "debit")       private BigDecimal debit;
    @Column(name = "credit")      private BigDecimal credit;
    @Column(name = "uploaded_at") private LocalDateTime uploadedAt;

    public Long getId() { return id; }
    public String getNoPaket() { return noPaket; } public void setNoPaket(String v) { noPaket = v; }
    public String getMaker() { return maker; } public void setMaker(String v) { maker = v; }
    public String getStatus() { return status; } public void setStatus(String v) { status = v; }
    public String getNote() { return note; } public void setNote(String v) { note = v; }
    public String getDecidedBy() { return decidedBy; } public void setDecidedBy(String v) { decidedBy = v; }
    public LocalDateTime getDecidedAt() { return decidedAt; } public void setDecidedAt(LocalDateTime v) { decidedAt = v; }
    public Integer getJml() { return jml; } public void setJml(Integer v) { jml = v; }
    public BigDecimal getDebit() { return debit; } public void setDebit(BigDecimal v) { debit = v; }
    public BigDecimal getCredit() { return credit; } public void setCredit(BigDecimal v) { credit = v; }
    public LocalDateTime getUploadedAt() { return uploadedAt; } public void setUploadedAt(LocalDateTime v) { uploadedAt = v; }
}
