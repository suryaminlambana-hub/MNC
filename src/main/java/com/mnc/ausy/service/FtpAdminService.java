package com.mnc.ausy.service;

import com.mnc.ausy.domain.ExportTransfer;
import com.mnc.ausy.repo.ExportTransferRepository;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Antrian &amp; riwayat pengiriman file export ke FTP.
 *
 * BUG_AUSY_00141 — pengaturan FTP (host, kredensial, folder) tidak lagi ada di sini. Halaman
 * "Konfigurasi FTP" dihapus dan setelannya hanya dibaca {@link FtpService} dari database/properti
 * deploy, sehingga tidak ada satu pun jalur di website yang bisa melihat atau mengubahnya. Yang
 * tersisa adalah pantauan antrian transfer — dipakai card beranda Admin beserta tombol Kirim Ulang.
 */
@Service
public class FtpAdminService {
    private final ExportTransferRepository transfers;
    private final FtpService ftp;

    public FtpAdminService(ExportTransferRepository transfers, FtpService ftp) {
        this.transfers = transfers; this.ftp = ftp;
    }

    /**
     * Dari mana setelan FTP yang berlaku dibaca — ditampilkan di card beranda Admin.
     *
     * Setelah layar Konfigurasi FTP dihapus (BUG_AUSY_00141) tidak ada lagi tempat melihat setelan
     * yang sedang dipakai, sehingga sisa baris {@code ftp_config} dari layar lama bisa menimpa .env
     * tanpa seorang pun tahu. Ini bukan mengembalikan layar konfigurasinya: host & kredensial tetap
     * tidak ditampilkan maupun bisa diubah dari website, yang disebut hanya SUMBER-nya.
     */
    public String configSource() { return ftp.sourceLabel(); }

    public List<ExportTransfer> recentTransfers() { return transfers.findTop50ByOrderByCreatedAtDesc(); }
    public long pendingCount() { return transfers.countByStatus("PENDING") + transfers.countByStatus("FAILED"); }

    /** Transfers stuck as PENDING/FAILED — Admin dapat mengirim ulang dari beranda. */
    public List<ExportTransfer> needsResend() { return transfers.findByStatusInOrderByCreatedAtDesc(List.of("PENDING", "FAILED")); }

    /** A maker's own export transfers that failed to reach FTP — shown as an alert on their dashboard. */
    public List<ExportTransfer> failedFor(String inputer) { return transfers.findByInputerAndStatusOrderByCreatedAtDesc(inputer, "FAILED"); }
}
