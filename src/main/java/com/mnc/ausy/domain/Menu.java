package com.mnc.ausy.domain;

import jakarta.persistence.*;

@Entity
@Table(name = "menu")
public class Menu {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id_menu")
    private Integer idMenu;

    @Column(name = "nama_menu", nullable = false)
    private String namaMenu;

    private String url;
    private String icon;
    private Integer urutan = 0;
    private Integer parent = 0;
    private Short active = 1;

    /** MENU = link sidebar, ACTION = izin URL saja, CARD = card dashboard (url 'card:<key>'). */
    private String kind = "MENU";

    @Transient
    private java.util.List<Menu> children = new java.util.ArrayList<>();

    public Integer getIdMenu() { return idMenu; }
    public void setIdMenu(Integer idMenu) { this.idMenu = idMenu; }
    public String getNamaMenu() { return namaMenu; }
    public void setNamaMenu(String namaMenu) { this.namaMenu = namaMenu; }
    public String getUrl() { return url; }
    public void setUrl(String url) { this.url = url; }
    public String getIcon() { return icon; }
    public void setIcon(String icon) { this.icon = icon; }
    public Integer getUrutan() { return urutan; }
    public void setUrutan(Integer urutan) { this.urutan = urutan; }
    public Integer getParent() { return parent; }
    public void setParent(Integer parent) { this.parent = parent; }
    public String getKind() { return kind; }
    public void setKind(String kind) { this.kind = kind; }
    /** Key card dashboard tanpa prefix 'card:' — kosong kalau baris ini bukan CARD. */
    @Transient
    public String getCardKey() {
        return url != null && url.startsWith("card:") ? url.substring(5) : "";
    }
    public Short getActive() { return active; }
    public void setActive(Short active) { this.active = active; }
    public java.util.List<Menu> getChildren() { return children; }
    public void setChildren(java.util.List<Menu> children) { this.children = children; }
}
