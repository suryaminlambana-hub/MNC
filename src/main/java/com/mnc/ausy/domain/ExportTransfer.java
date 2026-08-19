package com.mnc.ausy.domain;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/** One record per generated GL export file and its transfer-out status to the FTP server. */
@Entity
@Table(name = "export_transfer")
public class ExportTransfer {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "file_name", nullable = false) private String fileName;
    private String paket;
    private String inputer;
    private String checker;
    @Column(name = "rows_count") private Integer rowsCount = 0;

    /** SENT / PENDING / FAILED */
    @Column(nullable = false) private String status = "PENDING";
    @Column(name = "remote_path") private String remotePath;
    private String error;
    @Column(nullable = false) private Integer attempts = 0;
    @Column(name = "created_at", nullable = false) private LocalDateTime createdAt = LocalDateTime.now();
    @Column(name = "sent_at") private LocalDateTime sentAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getFileName() { return fileName; }
    public void setFileName(String fileName) { this.fileName = fileName; }
    public String getPaket() { return paket; }
    public void setPaket(String paket) { this.paket = paket; }
    public String getInputer() { return inputer; }
    public void setInputer(String inputer) { this.inputer = inputer; }
    public String getChecker() { return checker; }
    public void setChecker(String checker) { this.checker = checker; }
    public Integer getRowsCount() { return rowsCount; }
    public void setRowsCount(Integer rowsCount) { this.rowsCount = rowsCount; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getRemotePath() { return remotePath; }
    public void setRemotePath(String remotePath) { this.remotePath = remotePath; }
    public String getError() { return error; }
    public void setError(String error) { this.error = error; }
    public Integer getAttempts() { return attempts; }
    public void setAttempts(Integer attempts) { this.attempts = attempts; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getSentAt() { return sentAt; }
    public void setSentAt(LocalDateTime sentAt) { this.sentAt = sentAt; }
}
