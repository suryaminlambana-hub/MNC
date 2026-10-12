package com.mnc.ausy.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Menyalakan penjadwal Spring. Sebelum REQ_AUSY_00061 aplikasi ini tidak punya satu pun tugas
 * terjadwal, jadi dukungannya memang belum pernah dihidupkan.
 */
@Configuration
@EnableScheduling
public class SchedulingConfig { }
