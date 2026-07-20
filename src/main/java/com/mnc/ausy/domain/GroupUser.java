package com.mnc.ausy.domain;

import jakarta.persistence.*;

@Entity
@Table(name = "group_user")
public class GroupUser {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id_group")
    private Integer idGroup;

    @Column(name = "nama_group", nullable = false)
    private String namaGroup;

    private String level;
    private String inisial;

    public Integer getIdGroup() { return idGroup; }
    public void setIdGroup(Integer idGroup) { this.idGroup = idGroup; }
    public String getNamaGroup() { return namaGroup; }
    public void setNamaGroup(String namaGroup) { this.namaGroup = namaGroup; }
    public String getLevel() { return level; }
    public void setLevel(String level) { this.level = level; }
    public String getInisial() { return inisial; }
    public void setInisial(String inisial) { this.inisial = inisial; }
}
