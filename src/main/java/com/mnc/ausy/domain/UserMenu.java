package com.mnc.ausy.domain;

import jakarta.persistence.*;

/**
 * Hak akses yang benar-benar berlaku untuk SATU user (BUG_AUSY_00143).
 *
 * {@link GroupMenu} tetap ada sebagai cetakan milik role — dipakai sebagai nilai awal user baru
 * dan untuk pertanyaan setingkat role. Yang dibaca saat user membuka aplikasi adalah baris ini.
 */
@Entity
@Table(name = "user_menu")
public class UserMenu {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id")
    private String userId;

    @Column(name = "id_menu")
    private Integer idMenu;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }
    public Integer getIdMenu() { return idMenu; }
    public void setIdMenu(Integer idMenu) { this.idMenu = idMenu; }
}
