package com.mnc.ausy.domain;

import jakarta.persistence.*;

/**
 * Satu pasangan otorisasi: {@code checker} berwenang atas pengajuan & data GL milik {@code maker}
 * (BUG_AUSY_00139). Seorang checker boleh memegang beberapa maker; seorang maker boleh dipegang
 * lebih dari satu checker.
 */
@Entity
@Table(name = "checker_maker")
public class CheckerMaker {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String checker;
    private String maker;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getChecker() { return checker; }
    public void setChecker(String checker) { this.checker = checker; }
    public String getMaker() { return maker; }
    public void setMaker(String maker) { this.maker = maker; }
}
