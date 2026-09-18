package com.mnc.ausy.web;

import com.mnc.ausy.security.AusyUserDetails;
import com.mnc.ausy.service.GlExportService;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * Kirim ulang file export yang gagal/tertunda sampai ke FTP.
 *
 * BUG_AUSY_00141 — halaman "Konfigurasi FTP" dihapus: setelan FTP (host, kredensial, folder)
 * hanya dibaca dari database/properti deploy dan tidak lagi bisa dilihat atau diubah lewat
 * website. Kirim ulang bukan konfigurasi, jadi kemampuannya dipertahankan dan pindah ke card
 * "Export FTP Gagal / Tertunda" di beranda Admin.
 *
 * Hak aksesnya tetap terkunci: URL ini terdaftar sebagai item akses ACTION yang menempel pada
 * card tersebut (menu 606 di migrasi V31), sehingga hanya user yang memegang card-nya bisa
 * memanggilnya — bukan terbuka untuk semua yang login.
 */
@Controller
public class FtpResendController {
    private final GlExportService glExport;

    public FtpResendController(GlExportService glExport) { this.glExport = glExport; }

    @PostMapping("/admin/ftp-resend/{id}")
    public String resend(@PathVariable Long id, @AuthenticationPrincipal AusyUserDetails me, RedirectAttributes ra) {
        String msg = glExport.resend(id, me.getUsername());
        ra.addFlashAttribute(msg.startsWith("Berhasil") ? "ok" : "warn", msg);
        return "redirect:/dashboard";
    }
}
