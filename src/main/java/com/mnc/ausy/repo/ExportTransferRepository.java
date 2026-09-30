package com.mnc.ausy.repo;

import com.mnc.ausy.domain.ExportTransfer;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Catatan file hasil export. Query antrian/kegagalan kiriman sudah tidak ada: pengiriman FTP
 * dihapus, hasil export adalah file yang ditulis ke folder export sehingga tidak ada status
 * selain "sudah dibuat". Baris ini dibaca lewat id-nya dari permintaan export (Riwayat Export).
 */
public interface ExportTransferRepository extends JpaRepository<ExportTransfer, Long> {

    /**
     * File hasil generate yang dibuat pada satu rentang tanggal (BUG_AUSY_00167). Halaman
     * Generate File ACCTXT dulu hanya menampilkan export yang BELUM dibuatkan file, sehingga
     * memilih tanggal lampau selalu berakhir layar kosong — yang sudah jadi file justru hilang
     * dari pandangan.
     *
     * Batas atasnya eksklusif (bukan Between yang inklusif dua sisi) supaya file yang dibuat
     * tepat tengah malam tidak terhitung dua kali di dua rentang yang bersebelahan.
     */
    List<ExportTransfer> findByCreatedAtGreaterThanEqualAndCreatedAtLessThanOrderByCreatedAtDesc(
            LocalDateTime from, LocalDateTime until);

    /**
     * Catatan berkas untuk satu nama file (BUG_AUSY_00169). Satu nama = satu baris: penekanan
     * tombol berikutnya pada hari yang sama memperbarui baris ini, tidak menambah baris baru,
     * supaya daftar di layar tidak memuat dua baris untuk berkas yang sebenarnya satu.
     */
    Optional<ExportTransfer> findFirstByFileNameOrderByIdAsc(String fileName);
}
