package com.mnc.ausy.domain;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "users")
public class User {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false, unique = true)
    private String userId;

    @Column(name = "pass", nullable = false)
    private String pass;

    private String nama;

    @Column(name = "status_account")
    private Short statusAccount = 1;

    private String email;

    @Column(name = "id_group")
    private Integer idGroup;

    private String level;

    @Column(name = "last_login")
    private LocalDateTime lastLogin;

    @Column(name = "cre_date")
    private LocalDateTime creDate;

    private String addby;

    // getters / setters
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }
    public String getPass() { return pass; }
    public void setPass(String pass) { this.pass = pass; }
    public String getNama() { return nama; }
    public void setNama(String nama) { this.nama = nama; }
    public Short getStatusAccount() { return statusAccount; }
    public void setStatusAccount(Short statusAccount) { this.statusAccount = statusAccount; }
    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }
    public Integer getIdGroup() { return idGroup; }
    public void setIdGroup(Integer idGroup) { this.idGroup = idGroup; }
    public String getLevel() { return level; }
    public void setLevel(String level) { this.level = level; }
    public LocalDateTime getLastLogin() { return lastLogin; }
    public void setLastLogin(LocalDateTime lastLogin) { this.lastLogin = lastLogin; }
    public LocalDateTime getCreDate() { return creDate; }
    public void setCreDate(LocalDateTime creDate) { this.creDate = creDate; }
    public String getAddby() { return addby; }
    public void setAddby(String addby) { this.addby = addby; }
}
