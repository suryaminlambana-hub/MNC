package com.mnc.ausy.web;

import com.mnc.ausy.domain.GlExportRequest;
import com.mnc.ausy.security.AusyUserDetails;
import com.mnc.ausy.service.AcctxtGenerateService;
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

    public AcctxtGenerateController(AcctxtGenerateService acctxt) { this.acctxt = acctxt; }

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
        AcctxtGenerateService.Result r = acctxt.generate(from, to, me);
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
        AcctxtGenerateService.Result r = acctxt.generateToday(me);
        ra.addFlashAttribute(r.ok() ? "ok" : "warn", r.message());
        return "redirect:/admin/acctxt";
    }
}
