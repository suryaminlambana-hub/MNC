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

    /** Akun bawaan sistem (admin & admin approver utama) — tidak boleh dihapus (BUG_AUSY_00131). */
    @Column(name = "protected_account") private Short protectedAccount = 0;

    /** Kapan password terakhir diganti — dasar hitung mundur masa berlakunya (REQ_AUSY_00009). */
    @Column(name = "pass_changed_at") private LocalDateTime passChangedAt;

    /**
     * Pasang password baru SEKALIGUS mencatat tanggalnya.
     *
     * Dipakai di SEMUA tempat yang mengubah password. Memisahkan keduanya pernah dicoba dan
     * mudah sekali terlewat: satu jalur yang lupa mencatat tanggal membuat akun itu tidak pernah
     * muncul di pengingat, dan tidak ada yang menyadarinya sampai passwordnya benar-benar
     * kedaluwarsa — persis keluhan yang hendak diperbaiki.
     */
    public void gantiPassword(String hash) {
        this.pass = hash;
        this.passChangedAt = LocalDateTime.now();
    }

    // getters / setters
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }
    public String getPass() { return pass; }
    /** Menyetel hash TANPA mencatat tanggalnya — untuk mengubah password pakai {@link #gantiPassword}. */
    public void setPass(String pass) { this.pass = pass; }
    public LocalDateTime getPassChangedAt() { return passChangedAt; }
    public void setPassChangedAt(LocalDateTime v) { this.passChangedAt = v; }
    public String getNama() { return nama; }
    public void setNama(String nama) { this.nama = nama; }
    public boolean isProtectedAccount() { return protectedAccount != null && protectedAccount == 1; }
    public Short getProtectedAccount() { return protectedAccount; }
    public void setProtectedAccount(Short v) { this.protectedAccount = v; }
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
