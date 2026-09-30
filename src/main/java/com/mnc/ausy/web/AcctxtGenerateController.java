package com.mnc.ausy.web;

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
        model.addAttribute("pending", acctxt.pending(f, t));
        model.addAttribute("allPendingCount", acctxt.allPending().size());
        // BUG_AUSY_00167: yang sudah jadi file ikut ditampilkan, kalau tidak memilih tanggal
        // lampau selalu berakhir layar kosong — begitu digenerate, exportnya keluar dari
        // daftar "belum dibuatkan file" dan tidak muncul di mana pun lagi.
        model.addAttribute("generated", acctxt.generated(f, t));
        model.addAttribute("todayYymmdd", acctxt.todayYymmdd());
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
     * Terbitkan ulang satu file ACCTXT yang sudah pernah dibuat (BUG_AUSY_00168).
     *
     * Rentang tanggal ikut dikembalikan supaya admin mendarat di layar yang sama seperti sebelum
     * menekan tombolnya, lengkap dengan daftar file yang sedang dilihatnya.
     */
    @PostMapping("/admin/acctxt/regenerate")
    public String regenerate(@RequestParam Long transferId,
                             @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                             @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                             @AuthenticationPrincipal AusyUserDetails me, RedirectAttributes ra) {
        AcctxtGenerateService.Result r = acctxt.regenerate(transferId, me);
        ra.addFlashAttribute(r.ok() ? "ok" : "warn", r.message());
        return "redirect:/admin/acctxt?from=" + from + "&to=" + to;
    }
}
