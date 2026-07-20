package com.mnc.ausy.domain;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/** Immutable audit-trail row: a user action (login, navigation, security event). Append-only. */
@Entity
@Table(name = "tlog_activity")
public class TlogActivity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name="user_id")       private String userId;
    @Column(name="time_activity") private LocalDateTime timeActivity = LocalDateTime.now();
    private String ip;
    @Column(name="name_activity") private String nameActivity;
    private String info;

    public Long getId(){return id;}
    public String getUserId(){return userId;} public void setUserId(String v){userId=v;}
    public LocalDateTime getTimeActivity(){return timeActivity;} public void setTimeActivity(LocalDateTime v){timeActivity=v;}
    public String getIp(){return ip;} public void setIp(String v){ip=v;}
    public String getNameActivity(){return nameActivity;} public void setNameActivity(String v){nameActivity=v;}
    public String getInfo(){return info;} public void setInfo(String v){info=v;}
}
