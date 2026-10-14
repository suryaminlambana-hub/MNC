package com.mnc.ausy.web;

import com.mnc.ausy.domain.AcctxtSchedulerLog;
import com.mnc.ausy.service.AcctxtGenerateService;
import com.mnc.ausy.service.AcctxtSchedulerService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * Tombol Generate ACCTXT yang "ditekan" oleh file {@code ausy-trigger.jar} (REQ_AUSY_00058).
 * <p>
 * MNC menjadwalkan JAR itu sendiri (crontab, jam berapa pun yang mereka mau). JAR-nya hanya
 * memanggil alamat ini; yang dikerjakan di sini persis sama dengan tombol "Jalankan Sekarang" —
 * seluruh isi penampungan yang belum digenerate disusun jadi berkas ACCTXT, dikirim ke FTP,
 * dicatat di Riwayat dan audit trail dengan pelaku {@value #PELAKU}.
 * <p>
 * Dijaga token rahasia {@code AUSY_TRIGGER_TOKEN} di .env (header {@value #HEADER}), karena
 * pemanggilnya bukan user yang login. Token kosong = alamat ini dimatikan.
 * <p>
 * Jawabannya teks biasa supaya JAR-nya tidak butuh library apa pun: baris pertama
 * {@code BERHASIL}, {@code KOSONG} (penampungan memang kosong) atau {@code GAGAL}, baris
 * berikutnya pesannya.
 */
@RestController
public class AcctxtTriggerController {

    public static final String PELAKU = "jar-pemicu";
    public static final String HEADER = "X-AUSY-Token";
    public static final String PATH = "/api/acctxt/generate";

    private final AcctxtSchedulerService scheduler;
    private final String token;

    public AcctxtTriggerController(AcctxtSchedulerService scheduler,
                                   @Value("${ausy.trigger-token:}") String token) {
        this.scheduler = scheduler;
        this.token = token == null ? "" : token.trim();
    }

    @PostMapping(value = PATH, produces = MediaType.TEXT_PLAIN_VALUE + ";charset=UTF-8")
    public ResponseEntity<String> generate(@RequestHeader(value = HEADER, required = false) String diberikan) {
        if (token.isEmpty())
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body("GAGAL\nPemicu JAR belum diaktifkan: AUSY_TRIGGER_TOKEN kosong di .env server.");
        if (!sama(token, diberikan))
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body("GAGAL\nToken pemicu salah — samakan AUSY_TRIGGER_TOKEN dengan .env server.");

        AcctxtSchedulerLog hasil = scheduler.jalankan(PELAKU);
        boolean kosong = !hasil.isBerhasil() && AcctxtGenerateService.TIDAK_ADA_DATA.equals(hasil.getPesan());
        String status = hasil.isBerhasil() ? "BERHASIL" : kosong ? "KOSONG" : "GAGAL";
        String pesan = hasil.getPesan() == null ? "" : hasil.getPesan();
        return ResponseEntity.status(hasil.isBerhasil() || kosong ? HttpStatus.OK : HttpStatus.INTERNAL_SERVER_ERROR)
                .body(status + "\n" + pesan);
    }

    /** Perbandingan waktu-tetap, supaya token tidak bisa ditebak dari lamanya jawaban. */
    private static boolean sama(String a, String b) {
        if (b == null) return false;
        return MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.trim().getBytes(StandardCharsets.UTF_8));
    }
}
