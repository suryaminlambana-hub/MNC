package com.mnc.ausy.task;

import com.mnc.ausy.domain.AcctxtSchedulerLog;
import com.mnc.ausy.service.AcctxtGenerateService;
import com.mnc.ausy.service.AcctxtSchedulerService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;

/**
 * Mode tugas: menyusun berkas ACCTXT sekali lalu keluar (REQ_AUSY_00058).
 *
 * <pre>
 *   java -jar ausy-2.0.0.jar --ausy.task=generate-acctxt
 * </pre>
 *
 * Ada supaya penjadwalannya bisa dipegang crontab milik server, bukan hanya penjadwal di dalam
 * aplikasi. Yang dikerjakan PERSIS sama dengan tombol dan penjadwal — lewat
 * {@link AcctxtSchedulerService#jalankan()} — sehingga penyusunan berkas, pengiriman FTP,
 * penandaan "sudah digenerate", dan pencatatan riwayat tidak punya salinan kedua yang bisa
 * menyimpang sendiri.
 *
 * <p>Sengaja memakai JAR yang SAMA, bukan aplikasi terpisah: konfigurasi, koneksi basis data,
 * dan seluruh aturannya cukup ada di satu tempat.
 *
 * <p>Tabrakan dengan aplikasi web yang sedang berjalan dijaga kunci advisory Postgres di dalam
 * penyusunannya — gembok di dalam JVM tidak berlaku lintas proses.
 *
 * <p>Kode keluar: 0 bila berkasnya terbit, 0 juga bila penampungan memang kosong, dan 1 bila
 * penyusunannya gagal. Malam tanpa otorisasi sama sekali adalah hal yang wajar, jadi tidak
 * dilaporkan sebagai kegagalan — kalau dilaporkan, systemd menandai tugasnya "failed" hampir
 * setiap akhir pekan dan orang berhenti mempercayai tandanya. Dengan begitu crontab atau
 * pemantau mengenali kegagalan yang sungguhan tanpa harus membaca log.
 */
@Component
@ConditionalOnProperty(name = "ausy.task", havingValue = "generate-acctxt")
public class AcctxtTaskRunner implements ApplicationRunner {

    private static final Logger LOG = LoggerFactory.getLogger(AcctxtTaskRunner.class);

    private final AcctxtSchedulerService scheduler;
    private final ApplicationContext ctx;

    public AcctxtTaskRunner(AcctxtSchedulerService scheduler, ApplicationContext ctx) {
        this.scheduler = scheduler;
        this.ctx = ctx;
    }

    @Override
    public void run(ApplicationArguments args) {
        LOG.info("Mode tugas: menyusun berkas ACCTXT dari penampungan.");
        AcctxtSchedulerLog hasil = scheduler.jalankan();
        boolean kosong = !hasil.isBerhasil()
                && AcctxtGenerateService.TIDAK_ADA_DATA.equals(hasil.getPesan());
        LOG.info("Selesai — {}: {}",
                hasil.isBerhasil() ? "BERHASIL" : kosong ? "TIDAK ADA YANG DIKERJAKAN" : "GAGAL",
                hasil.getPesan());

        int kode = hasil.isBerhasil() || kosong ? 0 : 1;
        System.exit(SpringApplication.exit(ctx, () -> kode));
    }
}
