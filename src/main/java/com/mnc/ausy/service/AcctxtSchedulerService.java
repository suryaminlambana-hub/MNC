package com.mnc.ausy.service;

import com.mnc.ausy.domain.AcctxtScheduler;
import com.mnc.ausy.domain.AcctxtSchedulerLog;
import com.mnc.ausy.repo.AcctxtSchedulerLogRepository;
import com.mnc.ausy.repo.AcctxtSchedulerRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
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

    // ------------------------------------------------------------------ penjadwal

    /**
     * Setiap hari jam 23:00 WIB.
     *
     * Dimatikan lewat layar berarti jalannya dilewati — TIDAK dicatat sebagai kegagalan,
     * karena tidak ada yang gagal; riwayat yang penuh "dilewati" justru menyulitkan mencari
     * kegagalan yang sebenarnya.
     */
    // Jadwalnya bisa diubah lewat setelan tanpa menyentuh kode — terutama supaya bisa diuji
    // tanpa menunggu jam 23:00 sungguhan. Bawaannya tetap jam 23:00.
    @Scheduled(cron = "${ausy.scheduler-cron:0 0 23 * * *}", zone = "Asia/Jakarta")
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
    @Transactional
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
        } catch (Exception e) {
            log.setBerhasil(false);
            log.setPesan("Gagal: " + e.getMessage());
            LOG.error("Penjadwal ACCTXT gagal", e);
        }
        return riwayat.save(log);
    }
}
