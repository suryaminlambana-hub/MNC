package com.mnc.ausy.service;

import com.mnc.ausy.domain.AcctxtScheduler;
import com.mnc.ausy.domain.AcctxtSchedulerLog;
import com.mnc.ausy.repo.AcctxtSchedulerLogRepository;
import com.mnc.ausy.repo.AcctxtSchedulerRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;

/**
 * Penjadwal pembuatan berkas ACCTXT setiap jam 23:00 (REQ_AUSY_00061).
 *
 * Tombol manual tidak diganti — penjadwal ini TAMBAHAN, dan keduanya memanggil jalan yang sama
 * ({@link AcctxtGenerateService#generateToday(String)}). Kalau penjadwal menyusun berkasnya
 * sendiri, dua cara pembuatan akan berbeda perilaku begitu salah satunya diubah, dan yang
 * otomatis justru yang paling jarang diperiksa orang.
 *
 * Jam 23:00 memakai zona Asia/Jakarta, bukan zona mesin: server bisa saja berjalan dengan UTC,
 * dan "jam 11 malam" yang dimaksud tim accounting adalah waktu Jakarta.
 */
@Service
public class AcctxtSchedulerService {

    private static final Logger LOG = LoggerFactory.getLogger(AcctxtSchedulerService.class);

    /** Nama pelaku pada catatan audit & berkas yang dihasilkan penjadwal. */
    public static final String PELAKU = "scheduler";

    /** Jadwal bawaan: tiap hari jam 23:00. Dipakai juga kalau setelannya salah tulis. */
    public static final String CRON_BAWAAN = "0 0 23 * * *";
    public static final ZoneId ZONA = ZoneId.of("Asia/Jakarta");

    @Value("${ausy.scheduler-cron:" + CRON_BAWAAN + "}")
    private String cronSetelan;

    private final AcctxtSchedulerRepository setelan;
    private final AcctxtSchedulerLogRepository riwayat;
    private final AcctxtGenerateService acctxt;

    public AcctxtSchedulerService(AcctxtSchedulerRepository setelan, AcctxtSchedulerLogRepository riwayat,
                                  AcctxtGenerateService acctxt) {
        this.setelan = setelan; this.riwayat = riwayat; this.acctxt = acctxt;
    }

    // ------------------------------------------------------------------ setelan

    /** Baris setelan; dibuatkan kalau belum ada supaya layar tidak pernah kosong. */
    public AcctxtScheduler setelan() {
        return setelan.findById(AcctxtScheduler.ID).orElseGet(() -> {
            AcctxtScheduler s = new AcctxtScheduler();
            s.setAktif(true); s.setDiubahOleh("system"); s.setDiubahPada(LocalDateTime.now());
            return setelan.save(s);
        });
    }

    public boolean aktif() { return setelan().isAktif(); }

    @Transactional
    public boolean ubahAktif(boolean nyala, String olehSiapa) {
        AcctxtScheduler s = setelan();
        s.setAktif(nyala);
        s.setDiubahOleh(olehSiapa);
        s.setDiubahPada(LocalDateTime.now());
        setelan.save(s);
        return nyala;
    }

    // ------------------------------------------------------------------ riwayat

    /** Beberapa jalan terakhir, terbaru dulu. */
    public List<AcctxtSchedulerLog> riwayatTerakhir(int berapa) {
        return riwayat.findAllByOrderByMulaiDesc(PageRequest.of(0, berapa));
    }

    // ------------------------------------------------------------------ jadwal

    /**
     * Jadwal yang BENAR-BENAR berlaku.
     *
     * Setelan yang salah tulis tidak boleh menjatuhkan aplikasi. Dulu ekspresi cron dipasang
     * langsung di {@code @Scheduled}, dan satu salah ketik — misalnya gaya crontab 5 kolom
     * {@code 59 22 * * *}, padahal Spring menuntut 6 — membuat aplikasinya GAGAL START
     * seluruhnya, bukan sekadar penjadwalnya mati. Sekarang diperiksa dulu: kalau tidak sah,
     * dipakai jadwal bawaan dan sebabnya disebut di layar serta di log.
     */
    public String cron() {
        return cronSah() ? cronSetelan.trim() : CRON_BAWAAN;
    }

    public boolean cronSah() {
        try {
            CronExpression.parse(cronSetelan == null ? "" : cronSetelan.trim());
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /** Isi setelan apa adanya — ditampilkan saat salah tulis supaya jelas apa yang diperbaiki. */
    public String cronSetelan() { return cronSetelan; }

    /** Kapan penjadwal akan berjalan lagi; null kalau jadwalnya tidak sah. */
    public LocalDateTime jalanBerikutnya() {
        try {
            ZonedDateTime n = CronExpression.parse(cron()).next(ZonedDateTime.now(ZONA));
            return n == null ? null : n.toLocalDateTime();
        } catch (Exception e) {
            return null;
        }
    }

    // ------------------------------------------------------------------ penjadwal

    /**
     * Setiap hari jam 23:00 WIB.
     *
     * Dimatikan lewat layar berarti jalannya dilewati — TIDAK dicatat sebagai kegagalan,
     * karena tidak ada yang gagal; riwayat yang penuh "dilewati" justru menyulitkan mencari
     * kegagalan yang sebenarnya.
     */
    public void jalanTerjadwal() {
        if (!aktif()) {
            LOG.info("Penjadwal ACCTXT sedang dimatikan — jalan jam 23:00 dilewati.");
            return;
        }
        jalankan();
    }

    /**
     * Jalankan sekali dan catat hasilnya.
     *
     * Kegagalan apa pun ditangkap: penjadwal yang melempar exception akan berhenti diam-diam
     * dan baru ketahuan berhari-hari kemudian saat berkasnya dicari. Yang dicatat pesannya,
     * bukan hanya "gagal".
     */
    /**
     * SENGAJA tidak @Transactional.
     *
     * Kegagalan FTP membatalkan transaksi pembuatan berkas supaya data kembali ke penampungan.
     * Kalau pencatatan riwayat ikut satu transaksi dengan itu, catatannya ikut terhapus — dan
     * kegagalan yang paling perlu dilihat justru yang paling tidak meninggalkan jejak.
     */
    public AcctxtSchedulerLog jalankan() {
        AcctxtSchedulerLog log = new AcctxtSchedulerLog();
        log.setMulai(LocalDateTime.now());
        try {
            AcctxtGenerateService.Result r = acctxt.generateSemuaMenunggu(PELAKU);
            log.setBerhasil(r.ok());
            log.setPesan(r.message());
            log.setFileName(r.fileName());
            log.setRowsCount(r.rows());
            if (r.ok()) LOG.info("Penjadwal ACCTXT: {}", r.message());
            else        LOG.warn("Penjadwal ACCTXT tidak menghasilkan berkas: {}", r.message());
        } catch (FtpGagalException e) {
            // Berkasnya tersusun, pengirimannya yang gagal. Nama berkas dan jumlah barisnya ikut
            // dicatat supaya riwayat tidak menampilkan "- / 0 baris" untuk sesuatu yang isinya
            // sebenarnya sudah jadi.
            log.setBerhasil(false);
            log.setPesan(e.pesan());
            log.setFileName(e.getFileName());
            log.setRowsCount(e.getRows());
            LOG.warn("Penjadwal ACCTXT: {}", e.pesan());
        } catch (Exception e) {
            log.setBerhasil(false);
            log.setPesan("Gagal: " + e.getMessage());
            LOG.error("Penjadwal ACCTXT gagal", e);
        }
        return riwayat.save(log);
    }
}
