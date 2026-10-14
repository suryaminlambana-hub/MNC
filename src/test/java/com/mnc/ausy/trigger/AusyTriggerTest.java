package com.mnc.ausy.trigger;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** REQ_AUSY_00058 — ausy-trigger.jar: setelan dan kode keluarnya. */
class AusyTriggerTest {

    @TempDir Path dir;

    @Test
    void setelanDariEnvFile() throws Exception {
        Path env = dir.resolve(".env");
        Files.writeString(env, "# komentar\nDB_PASS=rahasia\nexport AUSY_TRIGGER_TOKEN=\"abc123\"\n"
                + "AUSY_TRIGGER_URL=http://10.5.99.24:8080/ausy/\n");
        AusyTrigger.Setelan s = AusyTrigger.setelan(new String[]{"--env=" + env}, Map.of());
        assertEquals("abc123", s.token());
        assertEquals("http://10.5.99.24:8080/ausy", s.url());     // garis miring di ujung dibuang
    }

    @Test
    void argumenMenangAtasLingkunganDanBerkas() throws Exception {
        Path env = dir.resolve(".env");
        Files.writeString(env, "AUSY_TRIGGER_TOKEN=dari-berkas\n");
        AusyTrigger.Setelan s = AusyTrigger.setelan(new String[]{"--env=" + env, "--token=dari-argumen"},
                Map.of("AUSY_TRIGGER_TOKEN", "dari-lingkungan"));
        assertEquals("dari-argumen", s.token());
        assertEquals(AusyTrigger.URL_BAWAAN, s.url());

        s = AusyTrigger.setelan(new String[]{"--env=" + env}, Map.of("AUSY_TRIGGER_TOKEN", "dari-lingkungan"));
        assertEquals("dari-lingkungan", s.token());
    }

    @Test
    void envDicariSendiriDiFolderProyekDariJar() throws Exception {
        // susunan seperti di laptop: <proyek>\.env dan <proyek>\target\ausy-2.0.0-trigger.jar
        Path proyek = Files.createDirectories(dir.resolve("AUSY-MNC-JAVA"));
        Path target = Files.createDirectories(proyek.resolve("target"));
        Files.writeString(proyek.resolve(".env"), "SERVER_PORT=9090\nCONTEXT_PATH=/ausy\nAUSY_TRIGGER_TOKEN=tok\n");
        // folder kerja di luar proyek, supaya yang ditemukan memang lewat folder JAR
        Path lain = Files.createDirectories(dir.resolve("lain").resolve("dalam"));
        AusyTrigger.Setelan s = AusyTrigger.setelan(new String[0], Map.of(), lain, target);
        assertEquals("tok", s.token());
        assertEquals("http://127.0.0.1:9090/ausy", s.url());          // ikut SERVER_PORT aplikasinya
        assertEquals(proyek.resolve(".env").toAbsolutePath().normalize(), s.envFile());
    }

    @Test
    void contextPathKosongBerartiRoot() throws Exception {
        Files.writeString(dir.resolve(".env"), "CONTEXT_PATH=\nAUSY_TRIGGER_TOKEN=tok\n");
        assertEquals("http://127.0.0.1:8080", AusyTrigger.setelan(new String[]{"--env=" + dir.resolve(".env")}, Map.of()).url());
    }

    @Test
    void berkasEnvYangDisebutTapiTidakAda() {
        assertThrows(java.io.IOException.class,
                () -> AusyTrigger.setelan(new String[]{"--env=" + dir.resolve("tidak-ada.env")}, Map.of()));
        assertEquals(2, AusyTrigger.jalankan(new String[]{"--env=" + dir.resolve("tidak-ada.env")}, Map.of()));
    }

    @Test
    void tanpaTokenTidakMemanggilApaPun() {
        assertEquals(2, AusyTrigger.jalankan(new String[]{"--env=" + dir.resolve("x")}, Map.of())
                ); // .env tidak ada
        assertEquals(2, AusyTrigger.jalankan(new String[0], Map.of()));
    }

    @Test
    void kodeKeluarDariJawabanAplikasi() {
        assertEquals(0, AusyTrigger.hasil(200, "BERHASIL\nFile ACCTXT20261014.TXT terbit"));
        assertEquals(0, AusyTrigger.hasil(200, "KOSONG\nTidak ada data"));
        assertEquals(1, AusyTrigger.hasil(500, "GAGAL\nFTP gagal"));
        assertEquals(2, AusyTrigger.hasil(401, "GAGAL\nToken pemicu salah"));
        assertEquals(2, AusyTrigger.hasil(404, "GAGAL\nPemicu JAR belum diaktifkan"));
        assertEquals(2, AusyTrigger.hasil(200, "<html>halaman login</html>"));
    }

    @Test
    void aplikasiTidakBisaDihubungi() {
        // port 9 (discard) di localhost: tidak ada yang mendengar → koneksi ditolak
        assertEquals(2, AusyTrigger.jalankan(new String[]{"--url=http://127.0.0.1:9/ausy", "--token=x"}, Map.of()));
    }
}
