package com.mnc.ausy.domain;

import jakarta.persistence.*;

@Entity
@Table(name = "group_menu")
public class GroupMenu {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "id_group")
    private Integer idGroup;

    @Column(name = "id_menu")
    private Integer idMenu;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Integer getIdGroup() { return idGroup; }
    public void setIdGroup(Integer idGroup) { this.idGroup = idGroup; }
    public Integer getIdMenu() { return idMenu; }
    public void setIdMenu(Integer idMenu) { this.idMenu = idMenu; }
}
