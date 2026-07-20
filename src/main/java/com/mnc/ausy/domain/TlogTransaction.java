package com.mnc.ausy.domain;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/** Immutable audit-trail row: a business transaction (upload, approve, reject, GL change). Append-only. */
@Entity
@Table(name = "tlog_transaction")
public class TlogTransaction {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name="user_id")       private String userId;
    @Column(name="time_activity") private LocalDateTime timeActivity = LocalDateTime.now();
    @Column(name="name_activity") private String nameActivity;
    private String tinfo;

    public Long getId(){return id;}
    public String getUserId(){return userId;} public void setUserId(String v){userId=v;}
    public LocalDateTime getTimeActivity(){return timeActivity;} public void setTimeActivity(LocalDateTime v){timeActivity=v;}
    public String getNameActivity(){return nameActivity;} public void setNameActivity(String v){nameActivity=v;}
    public String getTinfo(){return tinfo;} public void setTinfo(String v){tinfo=v;}
}
