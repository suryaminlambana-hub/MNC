package com.mnc.ausy.domain;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/** Maker-initiated export of active GL to ACCTXT; approved by checker (Otorisasi Export GL) → FTP. */
@Entity
@Table(name = "gl_export_request")
public class GlExportRequest {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "requested_by", nullable = false) private String requestedBy;
    @Column(name = "requester_group") private Integer requesterGroup;
    /** PENDING / APPROVED / REJECTED */
    @Column(nullable = false) private String status = "PENDING";
    @Column(name = "rows_count") private Integer rowsCount = 0;
    private BigDecimal debit = BigDecimal.ZERO;
    private BigDecimal credit = BigDecimal.ZERO;
    @Column(name = "file_name") private String fileName;
    @Column(name = "created_at", nullable = false) private LocalDateTime createdAt = LocalDateTime.now();
    @Column(name = "note_maker") private String noteMaker;
    private String checker;
    @Column(name = "decided_at") private LocalDateTime decidedAt;
    @Column(name = "note_checker") private String noteChecker;
    @Column(name = "transfer_id") private Long transferId;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getRequestedBy() { return requestedBy; }
    public void setRequestedBy(String requestedBy) { this.requestedBy = requestedBy; }
    public Integer getRequesterGroup() { return requesterGroup; }
    public void setRequesterGroup(Integer requesterGroup) { this.requesterGroup = requesterGroup; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public Integer getRowsCount() { return rowsCount; }
    public void setRowsCount(Integer rowsCount) { this.rowsCount = rowsCount; }
    public BigDecimal getDebit() { return debit; }
    public void setDebit(BigDecimal debit) { this.debit = debit; }
    public BigDecimal getCredit() { return credit; }
    public void setCredit(BigDecimal credit) { this.credit = credit; }
    public String getFileName() { return fileName; }
    public void setFileName(String fileName) { this.fileName = fileName; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public String getNoteMaker() { return noteMaker; }
    public void setNoteMaker(String noteMaker) { this.noteMaker = noteMaker; }
    public String getChecker() { return checker; }
    public void setChecker(String checker) { this.checker = checker; }
    public LocalDateTime getDecidedAt() { return decidedAt; }
    public void setDecidedAt(LocalDateTime decidedAt) { this.decidedAt = decidedAt; }
    public String getNoteChecker() { return noteChecker; }
    public void setNoteChecker(String noteChecker) { this.noteChecker = noteChecker; }
    public Long getTransferId() { return transferId; }
    public void setTransferId(Long transferId) { this.transferId = transferId; }

    @Transient public boolean isBalanced() {
        return debit != null && credit != null && debit.compareTo(credit) == 0;
    }
}
