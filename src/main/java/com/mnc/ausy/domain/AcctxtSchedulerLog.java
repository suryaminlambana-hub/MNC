package com.mnc.ausy.domain;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/**
 * Satu baris per jalannya penjadwal, berhasil maupun gagal (REQ_AUSY_00061).
 *
 * Kegagalan ikut dicatat justru karena itu yang paling perlu dilihat: tanpa riwayat ini
 * "kenapa berkas hari itu tidak terbit" hanya bisa ditebak dari gejala.
 */
@Entity
@Table(name = "acctxt_scheduler_log")
public class AcctxtSchedulerLog {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private LocalDateTime mulai;
    private boolean berhasil;
    private String pesan;
    @Column(name = "file_name") private String fileName;
    @Column(name = "rows_count") private Integer rowsCount = 0;

    public Long getId(){return id;}
    public LocalDateTime getMulai(){return mulai;} public void setMulai(LocalDateTime v){mulai=v;}
    public boolean isBerhasil(){return berhasil;} public void setBerhasil(boolean v){berhasil=v;}
    public String getPesan(){return pesan;} public void setPesan(String v){pesan=v;}
    public String getFileName(){return fileName;} public void setFileName(String v){fileName=v;}
    public Integer getRowsCount(){return rowsCount;} public void setRowsCount(Integer v){rowsCount=v;}
}
