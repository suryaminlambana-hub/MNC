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
     * Penulisan TERAKHIR atas sebuah nama berkas (BUG_AUSY_00178).
     *
     * Tiap penekanan tombol menambah barisnya sendiri, dan isi berkas yang berlaku adalah isi
     * penulisan terakhir — penekanan kedua pada hari yang sama menimpa yang pertama. Riwayat
     * penulisan sebelumnya sengaja disimpan: tanpa itu tidak ada cara membedakan "export yang
     * ada di berkas tanggal 30" dari "export yang sempat masuk lalu tertimpa", padahal tombol
     * Susun Ulang justru harus mengambil versi terakhirnya saja.
     */
    Optional<ExportTransfer> findFirstByFileNameOrderByIdDesc(String fileName);
}
