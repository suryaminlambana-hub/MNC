package com.mnc.ausy.domain;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/** Single-row (id=1) FTP server configuration, managed by Admin via the website. */
@Entity
@Table(name = "ftp_config")
public class FtpConfig {
    @Id
    private Integer id = 1;

    @Column(nullable = false) private String host = "";
    @Column(nullable = false) private Integer port = 21;
    @Column(nullable = false) private String username = "";
    @Column(nullable = false) private String password = "";
    @Column(name = "remote_dir", nullable = false) private String remoteDir = "";
    @Column(nullable = false) private Boolean secure = false;   // FTPS (FTP over TLS)
    @Column(nullable = false) private Boolean passive = true;
    @Column(nullable = false) private Boolean enabled = false;
    @Column(name = "updated_by") private String updatedBy;
    @Column(name = "updated_at") private LocalDateTime updatedAt;

    public Integer getId() { return id; }
    public void setId(Integer id) { this.id = id; }
    public String getHost() { return host; }
    public void setHost(String host) { this.host = host; }
    public Integer getPort() { return port; }
    public void setPort(Integer port) { this.port = port; }
    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }
    public String getPassword() { return password; }
    public void setPassword(String password) { this.password = password; }
    public String getRemoteDir() { return remoteDir; }
    public void setRemoteDir(String remoteDir) { this.remoteDir = remoteDir; }
    public Boolean getSecure() { return secure; }
    public void setSecure(Boolean secure) { this.secure = secure; }
    public Boolean getPassive() { return passive; }
    public void setPassive(Boolean passive) { this.passive = passive; }
    public Boolean getEnabled() { return enabled; }
    public void setEnabled(Boolean enabled) { this.enabled = enabled; }
    public String getUpdatedBy() { return updatedBy; }
    public void setUpdatedBy(String updatedBy) { this.updatedBy = updatedBy; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
