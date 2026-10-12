package com.mnc.ausy.domain;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/** Setelan penjadwal pembuatan berkas ACCTXT — satu baris saja, id selalu 1 (REQ_AUSY_00061). */
@Entity
@Table(name = "acctxt_scheduler")
public class AcctxtScheduler {

    /** Satu-satunya nilai yang sah; dikunci juga oleh CHECK di basis data. */
    public static final short ID = 1;

    @Id private Short id = ID;
    private boolean aktif = true;
    @Column(name = "diubah_oleh") private String diubahOleh;
    @Column(name = "diubah_pada") private LocalDateTime diubahPada;

    public Short getId(){return id;} public void setId(Short v){id=v;}
    public boolean isAktif(){return aktif;} public void setAktif(boolean v){aktif=v;}
    public String getDiubahOleh(){return diubahOleh;} public void setDiubahOleh(String v){diubahOleh=v;}
    public LocalDateTime getDiubahPada(){return diubahPada;} public void setDiubahPada(LocalDateTime v){diubahPada=v;}
}
