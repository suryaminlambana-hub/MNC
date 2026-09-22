package com.mnc.ausy.config;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Baca file {@code .env} di folder tempat aplikasi dijalankan.
 *
 * Di Linux {@code packaging/start.sh} yang meng-export isi {@code .env} sebelum menjalankan JAR,
 * jadi setelan seperti {@code AUSY_EXPORT_DIR} sampai ke aplikasi. Di Windows tidak ada padanannya:
 * aplikasi dijalankan langsung ({@code mvnw.cmd spring-boot:run}) dengan beberapa {@code set ...}
 * yang diingat orang saat itu — dan yang paling mudah terlupa justru {@code AUSY_EXPORT_DIR},
 * sehingga file ACCTXT diam-diam mendarat di {@code data/txt} bukan di folder tujuan (BUG_AUSY_00151).
 * Dengan ini satu file {@code .env} berlaku sama di semua cara menjalankan.
 *
 * Diparse sendiri, bukan sebagai {@code .properties}, supaya path Windows tidak rusak:
 * parser properties memperlakukan {@code \} sebagai escape, jadi {@code C:\inetpub\ftproot}
 * akan terbaca {@code C:inetpubftproot} — persis kesalahan yang paling sulit dilihat orang.
 *
 * Presedensinya paling rendah: variabel environment sungguhan & argumen command line tetap menang,
 * sehingga {@code set AUSY_EXPORT_DIR=...} untuk sekali jalan masih bisa menimpa isi {@code .env}.
 */
public class DotEnvEnvironmentPostProcessor implements EnvironmentPostProcessor {

    /** Lokasi yang dicoba, berurutan; yang pertama ada dipakai. */
    private static final List<String> CANDIDATES = List.of(".env", "../.env");

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment env, SpringApplication app) {
        String override = System.getenv("AUSY_ENV_FILE");
        for (String c : (override == null || override.isBlank() ? CANDIDATES : List.of(override))) {
            Path p = Paths.get(c);
            if (!Files.isRegularFile(p)) continue;
            Map<String, Object> values = parse(p);
            if (values.isEmpty()) return;
            env.getPropertySources().addLast(new MapPropertySource("dotenv:" + p.toAbsolutePath(), values));
            // Sengaja System.out: ini berjalan sebelum sistem log berdiri.
            System.out.println("[AUSY] Setelan tambahan dibaca dari " + p.toAbsolutePath() + " (" + values.size() + " baris)");
            return;
        }
    }

    private Map<String, Object> parse(Path file) {
        Map<String, Object> out = new HashMap<>();
        try {
            for (String raw : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                String line = raw.trim();
                if (line.isEmpty() || line.startsWith("#")) continue;
                if (line.startsWith("export ")) line = line.substring(7).trim();
                int eq = line.indexOf('=');
                if (eq <= 0) continue;
                String key = line.substring(0, eq).trim();
                String val = line.substring(eq + 1).trim();
                if (val.length() >= 2 && (val.startsWith("\"") && val.endsWith("\"")
                        || val.startsWith("'") && val.endsWith("'")))
                    val = val.substring(1, val.length() - 1);
                if (!key.isEmpty()) out.put(key, val);
            }
        } catch (Exception e) {
            System.out.println("[AUSY] File " + file + " tidak bisa dibaca: " + e.getMessage());
            return Map.of();
        }
        return out;
    }
}
