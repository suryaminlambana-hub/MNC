package com.mnc.ausy.domain;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "gl_code")
public class GlCode {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(nullable = false) private String code;
    @Column(nullable = false) private String position;   // D / C
    private String description;
    private Short status = 1;
    @Column(name = "created_by")    private String createdBy;
    @Column(name = "created_date")  private LocalDateTime createdDate;
    @Column(name = "modified_by")   private String modifiedBy;
    @Column(name = "modified_date") private LocalDateTime modifiedDate;

    @Transient private boolean used;
    @Transient private long usageCount;        // berapa baris GL memakai kode ini
    @Transient private long usagePending;      // di antaranya, yang masih menunggu otorisasi

    public long getUsageCount() { return usageCount; }
    public void setUsageCount(long v) { this.usageCount = v; }
    public long getUsagePending() { return usagePending; }
    public void setUsagePending(long v) { this.usagePending = v; }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getCode() { return code; }
    public void setCode(String code) { this.code = code; }
    public String getPosition() { return position; }
    public void setPosition(String position) { this.position = position; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public Short getStatus() { return status; }
    public void setStatus(Short status) { this.status = status; }
    public String getCreatedBy() { return createdBy; }
    public void setCreatedBy(String v) { this.createdBy = v; }
    public LocalDateTime getCreatedDate() { return createdDate; }
    public void setCreatedDate(LocalDateTime v) { this.createdDate = v; }
    public String getModifiedBy() { return modifiedBy; }
    public void setModifiedBy(String v) { this.modifiedBy = v; }
    public LocalDateTime getModifiedDate() { return modifiedDate; }
    public void setModifiedDate(LocalDateTime v) { this.modifiedDate = v; }
    public boolean isUsed() { return used; }
    public void setUsed(boolean used) { this.used = used; }
}
