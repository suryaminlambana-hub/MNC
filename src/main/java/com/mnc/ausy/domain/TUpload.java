package com.mnc.ausy.domain;
import jakarta.persistence.*;
import java.time.LocalDateTime;
@Entity @Table(name="t_upload")
public class TUpload {
    @Id @GeneratedValue(strategy=GenerationType.IDENTITY) private Long id;
    @Column(name="sess_id") private String sessId;
    @Column(name="file_name") private String fileName;
    @Column(name="jml_data") private Integer jmlData;
    @Column(name="user_id") private String userId;
    private String status;
    @Column(name="adddt") private LocalDateTime adddt;
    /** Nomor batch hasil upload ini (REQ_AUSY_00036) — supaya nama file bisa ditampilkan per batch. */
    @Column(name="no_paket") private String noPaket;
    public String getNoPaket() { return noPaket; }
    public void setNoPaket(String noPaket) { this.noPaket = noPaket; }
    public Long getId(){return id;} public void setId(Long v){id=v;}
    public String getSessId(){return sessId;} public void setSessId(String v){sessId=v;}
    public String getFileName(){return fileName;} public void setFileName(String v){fileName=v;}
    public Integer getJmlData(){return jmlData;} public void setJmlData(Integer v){jmlData=v;}
    public String getUserId(){return userId;} public void setUserId(String v){userId=v;}
    public String getStatus(){return status;} public void setStatus(String v){status=v;}
    public LocalDateTime getAdddt(){return adddt;} public void setAdddt(LocalDateTime v){adddt=v;}
}
