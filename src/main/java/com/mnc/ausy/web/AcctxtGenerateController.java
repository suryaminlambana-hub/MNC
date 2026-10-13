package com.mnc.ausy.web;

import com.mnc.ausy.domain.GlExportRequest;
import com.mnc.ausy.security.AusyUserDetails;
import com.mnc.ausy.service.AcctxtGenerateService;
import com.mnc.ausy.service.AcctxtSchedulerService;
import com.mnc.ausy.service.FtpGagalException;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.time.LocalDate;
import java.util.List;

/**
 * Menu admin "Generate File ACCTXT" (BUG_AUSY_00155 & BUG_AUSY_00158).
 *
 * Satu-satunya tempat file ACCTXT dibuat sejak otorisasi checker berhenti di status
 * (BUG_AUSY_00156).
 */
@Controller
public class AcctxtGenerateController {

    private final AcctxtGenerateService acctxt;
    private final AcctxtSchedulerService scheduler;

    public AcctxtGenerateController(AcctxtGenerateService acctxt, AcctxtSchedulerService scheduler) {
        this.acctxt = acctxt;
        this.scheduler = scheduler;
    }

    @GetMapping("/admin/acctxt")
    public String page(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                       @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                       Model model) {
        LocalDate min = acctxt.earliestSelectable(), max = acctxt.latestSelectable();
        // Bawaan: hari ini saja. Rentangnya dipersempit ke jendela yang diizinkan supaya
        // pratinjau tidak pernah menampilkan data yang tombol Generate-nya akan menolaknya.
        LocalDate f = from == null ? max : from, t = to == null ? max : to;
        if (f.isBefore(min)) f = min;
        if (t.isAfter(max)) t = max;
        if (t.isBefore(f)) t = f;

        model.addAttribute("min", min);
        model.addAttribute("max", max);
        model.addAttribute("from", f);
        model.addAttribute("to", t);
        model.addAttribute("maxDaysBack", AcctxtGenerateService.MAX_DAYS_BACK);
        // Daftar "Menunggu digenerate" berisi persis yang akan diambil tombol di bawahnya:
        // disetujui hari ini dan belum pernah dibuatkan berkas. Daftar dan tombol harus
        // menjanjikan hal yang sama — daftar yang lebih luas dari tombolnya adalah jebakan.
        List<GlExportRequest> pending = acctxt.todayPending();
        model.addAttribute("pending", pending);
        model.addAttribute("todayPendingCount", pending.size());
        model.addAttribute("stranded", acctxt.strandedPending());
        // REQ_AUSY_00061: status penjadwal + riwayat jalannya, supaya "kenapa berkas hari itu
        // tidak terbit" bisa dibaca, bukan ditebak.
        model.addAttribute("schedulerAktif", scheduler.aktif());
        model.addAttribute("schedulerDiizinkan", scheduler.diizinkanServer());
        model.addAttribute("schedulerAktifDiLayar", scheduler.aktifDiLayar());
        model.addAttribute("schedulerSetelan", scheduler.setelan());
        model.addAttribute("schedulerRiwayat", scheduler.riwayatTerakhir(10));
        // Jadwal yang BENAR-BENAR berlaku + kapan jalan berikutnya. Tanpa ini "penjadwalnya
        // tidak jalan" hanya bisa ditebak dari gejala.
        model.addAttribute("schedulerCron", scheduler.cron());
        model.addAttribute("schedulerCronSah", scheduler.cronSah());
        model.addAttribute("schedulerCronSetelan", scheduler.cronSetelan());
        model.addAttribute("schedulerBerikutnya", scheduler.jalanBerikutnya());
        // Jam server, bukan jam laptop pembaca. Ditaruh bersebelahan dengan "jalan berikutnya"
        // supaya pertanyaan "penjadwalnya ikut jam yang mana" terjawab sendiri di layar.
        model.addAttribute("schedulerSekarang",
                java.time.LocalDateTime.now(com.mnc.ausy.service.AcctxtSchedulerService.ZONA));
        // BUG_AUSY_00167: yang sudah jadi file ikut ditampilkan, kalau tidak memilih tanggal
        // lampau selalu berakhir layar kosong — begitu digenerate, exportnya keluar dari
        // daftar "belum dibuatkan file" dan tidak muncul di mana pun lagi.
        model.addAttribute("todayYymmdd", acctxt.todayYymmdd());
        model.addAttribute("exportDir", acctxt.exportDirText());
        return "admin/acctxt";
    }

    @PostMapping("/admin/acctxt/generate")
    public String generate(@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                           @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                           @AuthenticationPrincipal AusyUserDetails me, RedirectAttributes ra) {
        AcctxtGenerateService.Result r;
        try { r = acctxt.generate(from, to, me); }
        catch (FtpGagalException e) {
            acctxt.catatGagal(me == null ? "-" : me.getUsername(), e.pesan());
            ra.addFlashAttribute("warn", e.pesan());
            return "redirect:/admin/acctxt";
        }
        if (!r.ok()) acctxt.catatGagal(me == null ? "-" : me.getUsername(), r.message());
        ra.addFlashAttribute(r.ok() ? "ok" : "warn", r.message());
        return "redirect:/admin/acctxt?from=" + from + "&to=" + to;
    }

    /**
     * Tombol kedua: susun berkas dari export yang disetujui HARI INI (BUG_AUSY_00174).
     * Satu-satunya jalan bagi persetujuan hari ini, karena hari ini tidak bisa dipilih
     * pada rentang tanggal (BUG_AUSY_00172).
     */
    @PostMapping("/admin/acctxt/generate-today")
    public String generateToday(@AuthenticationPrincipal AusyUserDetails me, RedirectAttributes ra) {
        AcctxtGenerateService.Result r;
        try { r = acctxt.generateToday(me); }
        catch (FtpGagalException e) {
            acctxt.catatGagal(me == null ? "-" : me.getUsername(), e.pesan());
            ra.addFlashAttribute("warn", e.pesan());
            return "redirect:/admin/acctxt";
        }
        if (!r.ok()) acctxt.catatGagal(me == null ? "-" : me.getUsername(), r.message());
        ra.addFlashAttribute(r.ok() ? "ok" : "warn", r.message());
        return "redirect:/admin/acctxt";
    }

    /**
     * Nyalakan / matikan penjadwal otomatis (REQ_AUSY_00061).
     * Tombol generate manual tidak terpengaruh — keduanya memang berdampingan.
     */
    @PostMapping("/admin/acctxt/scheduler")
    public String ubahScheduler(@RequestParam boolean aktif,
                                @AuthenticationPrincipal AusyUserDetails me, RedirectAttributes ra) {
        // Saklar server menang atas tombol di layar; diperiksa lagi di sini karena alamatnya
        // bisa ditembak langsung.
        if (!scheduler.diizinkanServer()) {
            ra.addFlashAttribute("warn", "Penjadwal dimatikan lewat setelan server — tombol ini tidak berlaku.");
            return "redirect:/admin/acctxt";
        }
        scheduler.ubahAktif(aktif, me == null ? "-" : me.getUsername());
        ra.addFlashAttribute("ok", aktif
                ? "Penjadwal otomatis DINYALAKAN — berkas ACCTXT dibuat setiap hari jam 23:00."
                : "Penjadwal otomatis DIMATIKAN — berkas hanya dibuat lewat tombol di halaman ini.");
        return "redirect:/admin/acctxt";
    }

    /**
     * Jalankan penjadwal SEKARANG, tanpa menunggu jam 23:00 (REQ_AUSY_00061).
     *
     * Ada karena "penjadwalnya tidak jalan" nyaris mustahil dibuktikan kalau pembuktiannya
     * harus menunggu tengah malam. Yang dikerjakan persis sama dengan yang dikerjakan jam
     * 23:00, dan hasilnya masuk riwayat yang sama — jadi ini pengujian yang sesungguhnya,
     * bukan tiruan.
     */
    @PostMapping("/admin/acctxt/scheduler/jalankan")
    public String jalankanScheduler(@AuthenticationPrincipal AusyUserDetails me, RedirectAttributes ra) {
        // Saklar server menang atas tombol di layar; diperiksa lagi di sini karena alamatnya
        // bisa ditembak langsung.
        if (!scheduler.diizinkanServer()) {
            ra.addFlashAttribute("warn", "Penjadwal dimatikan lewat setelan server — tidak bisa dijalankan dari sini.");
            return "redirect:/admin/acctxt";
        }
        var log = scheduler.jalankan();
        ra.addFlashAttribute(log.isBerhasil() ? "ok" : "warn",
                "Penjadwal dijalankan manual: " + log.getPesan());
        return "redirect:/admin/acctxt";
    }
}
