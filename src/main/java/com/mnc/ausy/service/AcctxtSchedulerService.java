package com.mnc.ausy.service;

import com.mnc.ausy.domain.AcctxtScheduler;
import com.mnc.ausy.domain.AcctxtSchedulerLog;
import com.mnc.ausy.repo.AcctxtSchedulerLogRepository;
import com.mnc.ausy.repo.AcctxtSchedulerRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
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

    /**
     * Saklar tingkat server (REQ_AUSY_00061).
     *
     * Berbeda dari tombol nyala/mati di layar, yang tersimpan di basis data dan bisa diubah
     * petugas. Yang ini diputuskan saat memasang aplikasi: dipakai ketika sebuah rilis ingin
     * dipasang TANPA penjadwalnya hidup — fiturnya ikut terpasang, tapi dormant sampai
     * setelannya diubah dan aplikasi dinyalakan ulang.
     *
     * Ketika false: tugas terjadwalnya tidak didaftarkan sama sekali, susulan tidak dikerjakan,
     * dan tombol-tombolnya hilang dari layar — supaya tidak ada yang mengira bisa
     * menyalakannya dari sana.
     */
    @Value("${ausy.scheduler-enabled:true}")
    private boolean diizinkanServer;

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

    /** Saklar server: false = penjadwal dormant, apa pun isi tombol di layar. */
    public boolean diizinkanServer() { return diizinkanServer; }

    /** Benar-benar akan berjalan: diizinkan server DAN dinyalakan di layar. */
    public boolean aktif() { return diizinkanServer && setelan().isAktif(); }

    /** Isi tombol di layar, terlepas dari saklar server — untuk ditampilkan apa adanya. */
    public boolean aktifDiLayar() { return setelan().isAktif(); }

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

    /**
     * Jadwal TERAKHIR yang seharusnya sudah lewat, atau null kalau belum ada.
     *
     * Dipakai untuk menyusul jalan yang terlewat. CronExpression hanya bisa maju, jadi
     * dijalankan maju dari dua hari lalu dan yang terakhir sebelum sekarang itulah jawabannya.
     */
    public LocalDateTime jadwalTerakhirYangLewat() {
        try {
            CronExpression c = CronExpression.parse(cron());
            ZonedDateTime sekarang = ZonedDateTime.now(ZONA);
            ZonedDateTime calon = c.next(sekarang.minusDays(2));
            ZonedDateTime terakhir = null;
            // Dibatasi supaya jadwal yang sangat sering (uji coba) tidak membuat perulangan panjang.
            for (int i = 0; i < 5000 && calon != null && !calon.isAfter(sekarang); i++) {
                terakhir = calon;
                calon = c.next(calon);
            }
            return terakhir == null ? null : terakhir.toLocalDateTime();
        } catch (Exception e) {
            return null;
        }
    }

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
     * Menyusul jalan yang terlewat saat aplikasi mati (REQ_AUSY_00061).
     *
     * Penjadwal di dalam aplikasi tidak mungkin menyala ketika aplikasinya tidak hidup — itu
     * berlaku untuk penjadwal in-process mana pun. Yang bisa dilakukan: begitu aplikasi hidup
     * lagi, periksa apakah jadwal terakhir sudah lewat tanpa sempat dijalankan, lalu kerjakan
     * susulannya. Tanpa ini, server yang mati semalam berarti berkas hari itu tidak pernah
     * terbit dan tidak ada yang tahu.
     *
     * Sekali saja: setelah susulan tercatat, start berikutnya melihat catatan itu dan melewatinya.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void susulSaatStart() {
        if (!diizinkanServer) {
            LOG.info("Penjadwal ACCTXT dimatikan lewat setelan server (ausy.scheduler-enabled=false)"
                    + " — susulan tidak dikerjakan.");
            return;
        }
        if (!aktif()) {
            LOG.info("Penjadwal ACCTXT sedang dimatikan — pemeriksaan jalan terlewat dilewati.");
            return;
        }
        LocalDateTime seharusnya = jadwalTerakhirYangLewat();
        if (seharusnya == null) return;

        List<AcctxtSchedulerLog> terakhir = riwayatTerakhir(1);
        boolean sudahDijalankan = !terakhir.isEmpty()
                && terakhir.get(0).getMulai() != null
                && !terakhir.get(0).getMulai().isBefore(seharusnya);
        if (sudahDijalankan) return;

        LOG.warn("Jadwal {} terlewat (aplikasi tidak hidup saat itu) — dikerjakan susulan sekarang.",
                seharusnya);
        AcctxtSchedulerLog log = jalankan();
        log.setPesan("Susulan jadwal " + seharusnya + " yang terlewat: "
                + (log.getPesan() == null ? "" : log.getPesan()));
        riwayat.save(log);
    }

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
        return jalankan(PELAKU);
    }

    /** Sama dengan {@link #jalankan()}, dengan nama pelaku sendiri di audit trail. */
    public AcctxtSchedulerLog jalankan(String pelaku) {
        return catatJalan(pelaku, () -> acctxt.generateSemuaMenunggu(pelaku));
    }

    /**
     * Seperti tombol "Generate File ACCTXT Hari Ini": hanya export yang disetujui HARI INI dan
     * belum digenerate, dicatat di riwayat yang sama. Dipakai JAR pemicu yang dijadwalkan MNC
     * ({@link com.mnc.ausy.web.AcctxtTriggerController}, REQ_AUSY_00058).
     */
    public AcctxtSchedulerLog jalankanHariIni(String pelaku) {
        return catatJalan(pelaku, () -> acctxt.generateToday(pelaku));
    }

    private AcctxtSchedulerLog catatJalan(String pelaku, java.util.function.Supplier<AcctxtGenerateService.Result> kerja) {
        AcctxtSchedulerLog log = new AcctxtSchedulerLog();
        log.setMulai(LocalDateTime.now());
        try {
            AcctxtGenerateService.Result r = kerja.get();
            log.setBerhasil(r.ok());
            log.setPesan(r.message());
            log.setFileName(r.fileName());
            log.setRowsCount(r.rows());
            if (r.ok()) LOG.info("Penjadwal ACCTXT: {}", r.message());
            else {
                // Sukses sudah dicatat di dalam penyusunannya; yang gagal belum (REQ_AUSY_00059).
                acctxt.catatGagal(pelaku, r.message());
                LOG.warn("Penjadwal ACCTXT tidak menghasilkan berkas: {}", r.message());
            }
        } catch (FtpGagalException e) {
            // Berkasnya tersusun, pengirimannya yang gagal. Nama berkas dan jumlah barisnya ikut
            // dicatat supaya riwayat tidak menampilkan "- / 0 baris" untuk sesuatu yang isinya
            // sebenarnya sudah jadi.
            log.setBerhasil(false);
            log.setPesan(e.pesan());
            log.setFileName(e.getFileName());
            log.setRowsCount(e.getRows());
            acctxt.catatGagal(pelaku, e.pesan());
            LOG.warn("Penjadwal ACCTXT: {}", e.pesan());
        } catch (Exception e) {
            log.setBerhasil(false);
            log.setPesan("Gagal: " + e.getMessage());
            acctxt.catatGagal(pelaku, String.valueOf(e.getMessage()));
            LOG.error("Penjadwal ACCTXT gagal", e);
        }
        return riwayat.save(log);
    }
}
