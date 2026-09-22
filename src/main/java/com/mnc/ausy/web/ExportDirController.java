package com.mnc.ausy.web;

import com.mnc.ausy.service.FtpService;
import com.mnc.ausy.service.GlExportService;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * "Direktori Hasil Export" — halaman baca saja yang menunjukkan folder tujuan file ACCTXT
 * beserta isinya.
 *
 * BUG_AUSY_00151: setelah checker approve, pertanyaan pertama selalu "filenya ke mana?", dan
 * jawabannya cuma ada di konfigurasi server. Pesan sukses sekarang menyebut path-nya, dan link
 * ke halaman ini membuatnya bisa langsung dilihat — link {@code file://} dari halaman http
 * diblokir browser, jadi daftar isi folder inilah versi yang benar-benar bisa dipencet.
 *
 * File-nya tidak bisa diunduh dari sini: ACCTXT memang tidak untuk turun ke komputer siapa pun,
 * hanya diambil core dari direktori/FTP. Halaman ini juga tidak menampilkan kredensial FTP —
 * yang disebut hanya SUMBER setelannya, sama seperti card di beranda Admin (BUG_AUSY_00141).
 */
@Controller
public class ExportDirController {
    private final GlExportService glExport;
    private final FtpService ftp;

    public ExportDirController(GlExportService glExport, FtpService ftp) {
        this.glExport = glExport; this.ftp = ftp;
    }

    @GetMapping("/export-dir")
    public String view(Model model) {
        model.addAttribute("dir", glExport.exportDirView());
        model.addAttribute("ftpSource", ftp.sourceLabel());
        model.addAttribute("ftpActive", ftp.enabled());
        return "export-dir/index";
    }
}
