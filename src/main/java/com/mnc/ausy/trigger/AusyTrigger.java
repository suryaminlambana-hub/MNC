package com.mnc.ausy.trigger;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code ausy-trigger.jar} — "menekan" tombol Generate File ACCTXT di aplikasi AUSY yang sedang
 * berjalan, lalu selesai (REQ_AUSY_00058). Jadwalnya diatur MNC sendiri, mis. lewat crontab:
 *
 * <pre>
 *   0 23 * * *  java -jar /opt/ausy/ausy-trigger.jar --env=/opt/ausy/.env &gt;&gt; /opt/ausy/logs/trigger.log 2&gt;&amp;1
 * </pre>
 *
 * JAR ini sengaja tidak memakai Spring maupun library lain (hanya JDK), jadi berkasnya beberapa
 * KB saja dan tidak butuh akses basis data: seluruh pekerjaannya dilakukan aplikasi web, persis
 * seperti tombolnya — lihat {@code AcctxtTriggerController}.
 *
 * <p>Setelan, urutan yang menang lebih dulu:
 * <ol>
 *   <li>argumen {@code --url=...}, {@code --token=...}</li>
 *   <li>variabel lingkungan {@code AUSY_TRIGGER_URL}, {@code AUSY_TRIGGER_TOKEN}</li>
 *   <li>berkas .env ({@code --env=/opt/ausy/.env}, bawaan {@code .env} di folder kerja)</li>
 * </ol>
 * URL bawaan {@code http://127.0.0.1:8080/ausy}.
 *
 * <p>Kode keluar: <b>0</b> berkas terbit atau penampungan memang kosong, <b>1</b> penyusunannya
 * gagal, <b>2</b> aplikasi tidak bisa dihubungi / token salah / setelan kurang.
 */
public final class AusyTrigger {

    static final String URL_BAWAAN = "http://127.0.0.1:8080/ausy";
    static final String PATH = "/api/acctxt/generate";
    static final String HEADER = "X-AUSY-Token";

    private AusyTrigger() {}

    public static void main(String[] args) {
        System.exit(jalankan(args, System.getenv()));
    }

    /** Setelan yang sudah diputuskan dari argumen, lingkungan, dan berkas .env (null = tidak ada). */
    record Setelan(String url, String token, Path envFile) {}

    static Setelan setelan(String[] args, Map<String, String> env) throws IOException {
        return setelan(args, env, Path.of(""), folderJar());
    }

    static Setelan setelan(String[] args, Map<String, String> env, Path folderKerja, Path folderJar) throws IOException {
        Map<String, String> arg = new HashMap<>();
        for (String a : args)
            if (a.startsWith("--") && a.contains("=")) arg.put(a.substring(2, a.indexOf('=')), a.substring(a.indexOf('=') + 1));

        // .env yang dipakai: yang disebut lewat --env, atau dicari sendiri di tempat yang sama dengan
        // aplikasinya (folder kerja dan satu di atasnya) ditambah folder JAR ini dan satu di atasnya —
        // jadi "java -jar target\ausy-2.0.0-trigger.jar" dari folder proyek, atau dari mana pun,
        // menemukan .env proyeknya tanpa harus menyebut path.
        Path envFile = null;
        Map<String, String> berkas = new HashMap<>();
        if (arg.containsKey("env")) {
            envFile = Path.of(arg.get("env"));
            berkas = bacaEnv(envFile, true);
        } else {
            List<Path> calon = new java.util.ArrayList<>(List.of(folderKerja.resolve(".env"), folderKerja.resolve("..").resolve(".env")));
            if (folderJar != null) { calon.add(folderJar.resolve(".env")); calon.add(folderJar.resolve("..").resolve(".env")); }
            for (Path p : calon)
                if (Files.isRegularFile(p)) { envFile = p.toAbsolutePath().normalize(); berkas = bacaEnv(p, true); break; }
        }

        // Alamat bawaan mengikuti port & context path aplikasinya sendiri (SERVER_PORT, CONTEXT_PATH di .env).
        String port = pilih(env.get("SERVER_PORT"), berkas.get("SERVER_PORT"), "8080");
        String ctx = berkas.containsKey("CONTEXT_PATH") ? berkas.get("CONTEXT_PATH") : "/ausy";
        String urlBawaan = "http://127.0.0.1:" + port + (ctx.isBlank() || ctx.startsWith("/") ? ctx : "/" + ctx);
        String url = pilih(arg.get("url"), env.get("AUSY_TRIGGER_URL"), berkas.get("AUSY_TRIGGER_URL"), urlBawaan);
        String token = pilih(arg.get("token"), env.get("AUSY_TRIGGER_TOKEN"), berkas.get("AUSY_TRIGGER_TOKEN"), "");
        while (url.endsWith("/")) url = url.substring(0, url.length() - 1);
        return new Setelan(url, token, envFile);
    }

    /** Folder tempat berkas JAR ini berada (null bila tidak bisa diketahui, mis. saat tes). */
    static Path folderJar() {
        try {
            Path p = Path.of(AusyTrigger.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            return Files.isRegularFile(p) ? p.getParent() : null;
        } catch (Exception e) {
            return null;
        }
    }

    static int jalankan(String[] args, Map<String, String> env) {
        Setelan s;
        try { s = setelan(args, env); }
        catch (IOException e) { tulis("GAGAL - " + e.getMessage()); return 2; }
        if (s.token().isEmpty()) {
            tulis("GAGAL - AUSY_TRIGGER_TOKEN belum diisi. " + (s.envFile() == null
                    ? "Berkas .env tidak ditemukan di folder ini maupun folder proyek; sebutkan dengan --env=<path .env>."
                    : "Tambahkan baris AUSY_TRIGGER_TOKEN=... di " + s.envFile() + " (nilainya sama dengan .env aplikasi)."));
            return 2;
        }
        tulis("memanggil " + s.url() + (s.envFile() == null ? "" : " (setelan dari " + s.envFile() + ")"));

        HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();
        HttpRequest req = HttpRequest.newBuilder(URI.create(s.url() + PATH))
                .timeout(Duration.ofMinutes(30))              // penyusunan + kirim FTP bisa lama
                .header(HEADER, s.token())
                .POST(HttpRequest.BodyPublishers.noBody())
                .build();
        HttpResponse<String> res;
        try {
            res = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (IOException e) {
            tulis("GAGAL - aplikasi AUSY tidak bisa dihubungi di " + s.url() + " (" + e + ")");
            return 2;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            tulis("GAGAL - dihentikan sebelum selesai.");
            return 2;
        }
        return hasil(res.statusCode(), res.body());
    }

    /** Terjemahkan jawaban aplikasi menjadi kode keluar dan satu baris log. */
    static int hasil(int status, String body) {
        String[] baris = (body == null ? "" : body).split("\n", 2);
        String kode = baris[0].trim(), pesan = baris.length > 1 ? baris[1].trim() : "";
        switch (kode) {
            case "BERHASIL" -> { tulis("BERHASIL - " + pesan); return 0; }
            case "KOSONG"   -> { tulis("TIDAK ADA YANG DIKERJAKAN - " + pesan); return 0; }
            case "GAGAL"    -> {
                tulis("GAGAL - " + pesan + " (HTTP " + status + ")");
                // 401/404 = token salah / pemicu belum diaktifkan: soal setelan, bukan penyusunan.
                return status == 401 || status == 404 ? 2 : 1;
            }
            default -> {
                tulis("GAGAL - jawaban tidak dikenal (HTTP " + status + "). Pastikan AUSY_TRIGGER_URL menunjuk"
                        + " ke aplikasi AUSY, mis. " + URL_BAWAAN + ".");
                return 2;
            }
        }
    }

    /** Isi berkas .env (KUNCI=nilai). Tidak ada berkasnya = kosong, kecuali disebut lewat --env. */
    static Map<String, String> bacaEnv(Path p, boolean wajib) throws IOException {
        Map<String, String> out = new HashMap<>();
        if (!Files.isRegularFile(p)) {
            if (wajib) throw new IOException("berkas .env tidak ditemukan: " + p.toAbsolutePath());
            return out;
        }
        List<String> lines = Files.readAllLines(p, StandardCharsets.UTF_8);
        for (String l : lines) {
            String t = l.trim();
            if (t.isEmpty() || t.startsWith("#") || !t.contains("=")) continue;
            if (t.startsWith("export ")) t = t.substring(7).trim();
            String k = t.substring(0, t.indexOf('=')).trim(), v = t.substring(t.indexOf('=') + 1).trim();
            if (v.length() >= 2 && (v.startsWith("\"") && v.endsWith("\"") || v.startsWith("'") && v.endsWith("'")))
                v = v.substring(1, v.length() - 1);
            out.put(k, v);
        }
        return out;
    }

    private static String pilih(String... calon) {
        for (String c : calon) if (c != null && !c.isBlank()) return c.trim();
        return "";
    }

    private static void tulis(String s) {
        System.out.println(LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
                + " AUSY scheduler Generate ACCTXT: " + ascii(s));
    }

    /** Tanda pisah panjang jadi "-": konsol Windows / log cron tidak selalu UTF-8. Isi lain tidak diubah. */
    static String ascii(String s) {
        return s.replace('—', '-');
    }
}
