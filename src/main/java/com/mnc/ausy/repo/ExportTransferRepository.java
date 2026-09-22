package com.mnc.ausy.repo;

import com.mnc.ausy.domain.ExportTransfer;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Catatan file hasil export. Query antrian/kegagalan kiriman sudah tidak ada: pengiriman FTP
 * dihapus, hasil export adalah file yang ditulis ke folder export sehingga tidak ada status
 * selain "sudah dibuat". Baris ini dibaca lewat id-nya dari permintaan export (Riwayat Export).
 */
public interface ExportTransferRepository extends JpaRepository<ExportTransfer, Long> {
}
