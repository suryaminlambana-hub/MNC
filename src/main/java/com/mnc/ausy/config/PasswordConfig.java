package com.mnc.ausy.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * Penyandi password, dipisahkan dari SecurityConfig.
 *
 * Bukan urusan web: {@code ConfigChangeService} memakainya untuk menyimpan password user, dan
 * layanan itu ikut hidup pada mode tugas yang berjalan tanpa Tomcat (REQ_AUSY_00058). Selama
 * bean ini menumpang di SecurityConfig — yang kini khusus web — mode tugas gagal start hanya
 * karena tidak menemukannya.
 */
@Configuration
public class PasswordConfig {

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();   // menerima hash $2y$ warisan aplikasi PHP
    }
}
