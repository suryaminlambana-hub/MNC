package com.mnc.ausy.domain;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/** A pending Add/Edit/Delete to a GL row, awaiting Checker approval (Workflow 8.3). */
@Entity
@Table(name = "gl_change_request")
public class GlChangeRequest {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private String op;                          // ADD / EDIT / DELETE / BULK_EDIT / IMPORT
    private String target = "GL_ROW";           // GL_ROW / GL_CODE / PAKET
    @Column(name="ref_id")      private Long refId;      // gl_code.id (target GL_CODE)
    private String inputer;                     // gl_tabel.gltrus (target PAKET)
    @Column(name="gl_id")       private Long glId;
    @Column(name="no_paket")    private String noPaket;
    private String maker;
    @Column(name="maker_group") private Integer makerGroup;
    private String checker;
    private String status = "PENDING";          // PENDING / APPROVED / REJECTED
    @Column(name="before_json") private String beforeJson;
    @Column(name="after_json")  private String afterJson;
    @Column(name="note_maker")  private String noteMaker;
    @Column(name="note_checker")private String noteChecker;
    @Column(name="created_at")  private LocalDateTime createdAt = LocalDateTime.now();
    @Column(name="decided_at")  private LocalDateTime decidedAt;

    public Long getId(){return id;}
    public String getOp(){return op;} public void setOp(String v){op=v;}
    public String getTarget(){return target;} public void setTarget(String v){target=v;}
    public Long getRefId(){return refId;} public void setRefId(Long v){refId=v;}
    public String getInputer(){return inputer;} public void setInputer(String v){inputer=v;}
    public Long getGlId(){return glId;} public void setGlId(Long v){glId=v;}
    public String getNoPaket(){return noPaket;} public void setNoPaket(String v){noPaket=v;}
    public String getMaker(){return maker;} public void setMaker(String v){maker=v;}
    public Integer getMakerGroup(){return makerGroup;} public void setMakerGroup(Integer v){makerGroup=v;}
    public String getChecker(){return checker;} public void setChecker(String v){checker=v;}
    public String getStatus(){return status;} public void setStatus(String v){status=v;}
    public String getBeforeJson(){return beforeJson;} public void setBeforeJson(String v){beforeJson=v;}
    public String getAfterJson(){return afterJson;} public void setAfterJson(String v){afterJson=v;}
    public String getNoteMaker(){return noteMaker;} public void setNoteMaker(String v){noteMaker=v;}
    public String getNoteChecker(){return noteChecker;} public void setNoteChecker(String v){noteChecker=v;}
    public LocalDateTime getCreatedAt(){return createdAt;} public void setCreatedAt(LocalDateTime v){createdAt=v;}
    public LocalDateTime getDecidedAt(){return decidedAt;} public void setDecidedAt(LocalDateTime v){decidedAt=v;}
}
