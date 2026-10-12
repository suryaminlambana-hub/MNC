package com.mnc.ausy.config;

import com.mnc.ausy.service.AcctxtSchedulerService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.SchedulingConfigurer;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;
import org.springframework.scheduling.support.CronTrigger;

import java.util.TimeZone;

/**
 * Menyalakan penjadwal Spring dan mendaftarkan tugas ACCTXT (REQ_AUSY_00061).
 *
 * Didaftarkan di sini, bukan lewat {@code @Scheduled}, supaya ekspresi cron-nya bisa DIPERIKSA
 * lebih dulu. Dengan {@code @Scheduled} ekspresi dibaca Spring sendiri saat start, dan satu
 * salah tulis — misalnya gaya crontab 5 kolom {@code 59 22 * * *}, padahal Spring menuntut 6 —
 * membuat aplikasinya gagal start seluruhnya. Setelan kenyamanan tidak boleh sanggup
 * menjatuhkan aplikasi.
 */
@Configuration
@EnableScheduling
public class SchedulingConfig implements SchedulingConfigurer {

    private static final Logger LOG = LoggerFactory.getLogger(SchedulingConfig.class);

    private final AcctxtSchedulerService scheduler;

    public SchedulingConfig(AcctxtSchedulerService scheduler) { this.scheduler = scheduler; }

    @Override
    public void configureTasks(ScheduledTaskRegistrar registrar) {
        if (!scheduler.cronSah())
            LOG.warn("Jadwal penjadwal ACCTXT tidak sah: \"{}\". Dipakai jadwal bawaan {}."
                    + " Ekspresi Spring berisi 6 kolom (detik menit jam tanggal bulan hari).",
                    scheduler.cronSetelan(), AcctxtSchedulerService.CRON_BAWAAN);

        String cron = scheduler.cron();
        registrar.addTriggerTask(
                scheduler::jalanTerjadwal,
                new CronTrigger(cron, TimeZone.getTimeZone(AcctxtSchedulerService.ZONA)));

        LOG.info("Penjadwal ACCTXT terpasang: cron \"{}\" zona {} — jalan berikutnya {}",
                cron, AcctxtSchedulerService.ZONA, scheduler.jalanBerikutnya());
    }
}
