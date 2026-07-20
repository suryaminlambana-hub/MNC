package com.mnc.ausy.domain;

import jakarta.persistence.*;

@Entity
@Table(name = "approval_group")
public class ApprovalGroup {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "approver_group") private Integer approverGroup;
    @Column(name = "target_group")   private Integer targetGroup;

    @Transient private String approverName;
    @Transient private String targetName;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Integer getApproverGroup() { return approverGroup; }
    public void setApproverGroup(Integer v) { this.approverGroup = v; }
    public Integer getTargetGroup() { return targetGroup; }
    public void setTargetGroup(Integer v) { this.targetGroup = v; }
    public String getApproverName() { return approverName; }
    public void setApproverName(String v) { this.approverName = v; }
    public String getTargetName() { return targetName; }
    public void setTargetName(String v) { this.targetName = v; }
}
