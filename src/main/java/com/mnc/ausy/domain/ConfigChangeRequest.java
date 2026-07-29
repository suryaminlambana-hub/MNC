package com.mnc.ausy.domain;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/** A pending Add/Edit/Delete to a config entity (USER), awaiting Admin Approval (②). */
@Entity
@Table(name = "config_change_request")
public class ConfigChangeRequest {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private String entity = "USER";              // what is being changed
    private String op;                           // ADD / EDIT / DELETE
    @Column(name="target_id")   private String targetId;   // user_id (null for ADD)
    private String maker;
    @Column(name="maker_group") private Integer makerGroup;
    private String checker;
    private String status = "PENDING";           // PENDING / APPROVED / REJECTED
    @Column(name="before_json") private String beforeJson;
    @Column(name="after_json")  private String afterJson;
    @Column(name="note_maker")  private String noteMaker;
    @Column(name="note_checker")private String noteChecker;
    @Column(name="created_at")  private LocalDateTime createdAt = LocalDateTime.now();
    @Column(name="decided_at")  private LocalDateTime decidedAt;

    public Long getId(){return id;}
    public String getEntity(){return entity;} public void setEntity(String v){entity=v;}
    public String getOp(){return op;} public void setOp(String v){op=v;}
    public String getTargetId(){return targetId;} public void setTargetId(String v){targetId=v;}
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
