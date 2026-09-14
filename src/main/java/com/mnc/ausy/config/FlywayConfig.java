package com.mnc.ausy.config;

import org.flywaydb.core.Flyway;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.flyway.FlywayMigrationStrategy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Selaraskan riwayat migrasi sebelum menjalankan migrasi baru.
 *
 * <p>Flyway menolak start bila checksum sebuah migrasi yang sudah diterapkan berbeda dengan
 * berkasnya — termasuk bila yang berubah hanya komentar. Itu membuat aplikasi mati di satu
 * lingkungan (mis. DEV) ketika berkas disesuaikan agar cocok dengan lingkungan lain (mis. laptop
 * developer), dan sebaliknya. {@code repair()} menyamakan checksum di tabel riwayat dengan berkas
 * yang ada tanpa menjalankan ulang SQL-nya, lalu {@code migrate()} menerapkan migrasi yang memang
 * belum pernah dijalankan.
 *
 * <p>Ini <b>bukan</b> izin untuk mengubah migrasi yang sudah dijalankan: kalau yang diubah adalah
 * perintah SQL-nya (bukan komentar), database lama tidak akan ikut berubah dan skemanya menyimpang
 * diam-diam. Aturannya tetap: perubahan hasil migrasi harus lewat migrasi baru.
 *
 * <p>Dapat dimatikan lewat {@code AUSY_FLYWAY_AUTO_REPAIR=false} bila kebijakan lingkungan
 * produksi menghendaki kegagalan yang keras saat ada penyimpangan.
 */
@Configuration
public class FlywayConfig {

    @Bean
    public FlywayMigrationStrategy flywayMigrationStrategy(
            @Value("${ausy.flyway.auto-repair:true}") boolean autoRepair) {
        return (Flyway flyway) -> {
            if (autoRepair) flyway.repair();
            flyway.migrate();
        };
    }
}
