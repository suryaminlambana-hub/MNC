package com.mnc.ausy.repo;

import com.mnc.ausy.domain.GlExportRequest;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.time.LocalDateTime;
import java.util.List;

public interface GlExportRequestRepository extends JpaRepository<GlExportRequest, Long> {
    List<GlExportRequest> findByStatusOrderByCreatedAtDesc(String status);
    long countByStatus(String status);
    List<GlExportRequest> findByRequestedByAndStatusOrderByCreatedAtDesc(String requestedBy, String status);
    List<GlExportRequest> findByRequestedByOrderByCreatedAtDesc(String requestedBy);

    /**
     * Export yang sudah disetujui checker tapi belum pernah ikut file ACCTXT (BUG_AUSY_00156).
     * file_name masih null = belum pernah digenerate; setelah generate, kolom itu diisi nama
     * file sehingga export yang sama tidak bisa ikut dua file.
     */
    @Query("select r from GlExportRequest r where r.status = 'APPROVED' and r.fileName is null"
         + " and r.decidedAt >= :from and r.decidedAt < :until order by r.decidedAt asc, r.id asc")
    List<GlExportRequest> findApprovedNotGenerated(@Param("from") LocalDateTime from,
                                                  @Param("until") LocalDateTime until);

    /** Idem, tanpa batas tanggal. */
    @Query("select r from GlExportRequest r where r.status = 'APPROVED' and r.fileName is null"
         + " order by r.decidedAt asc, r.id asc")
    List<GlExportRequest> findApprovedNotGenerated();

    /**
     * Export yang ikut satu file ACCTXT tertentu — dipakai menerbitkan ulang file itu apa adanya
     * (BUG_AUSY_00168). Urut id supaya isi berkasnya sama persis dengan waktu pertama dibuat.
     */
    List<GlExportRequest> findByTransferIdOrderById(Long transferId);

    /**
     * Seluruh export yang menghuni satu berkas ACCTXT (BUG_AUSY_00169).
     *
     * Berkas hari ini bisa terisi lewat beberapa kali penekanan tombol; isinya selalu disusun
     * ulang dari SEMUA penghuninya, bukan hanya dari yang baru ditambahkan pada penekanan
     * terakhir. Urut id supaya susunan barisnya tidak berubah-ubah antar penulisan.
     */
    List<GlExportRequest> findByFileNameOrderById(String fileName);

    /**
     * Export yang BOLEH mengisi berkas hari ini untuk rentang tanggal terpilih: yang belum punya
     * berkas, ditambah yang sudah menghuni berkas hari ini — supaya menekan tombol dua kali untuk
     * rentang yang sama tidak mengubah isinya.
     *
     * Yang sudah menghuni berkas HARI LAIN sengaja tidak ikut: memindahkannya adalah tindakan
     * tersendiri, bukan efek samping memilih rentang tanggal. (Tombol Populate Ulang yang dulu
     * mengerjakannya sudah dibatalkan — BUG_AUSY_00173.)
     */
    @Query("select r from GlExportRequest r where r.status = 'APPROVED'"
         + " and (r.fileName is null or r.fileName = :file)"
         + " and r.decidedAt >= :from and r.decidedAt < :until order by r.id asc")
    List<GlExportRequest> findForFileInRange(@Param("file") String file,
                                             @Param("from") LocalDateTime from,
                                             @Param("until") LocalDateTime until);

    /**
     * Penghuni berkas hari ini yang jatuh DI LUAR rentang yang baru dipilih (BUG_AUSY_00169).
     *
     * Mereka harus dilepas kembali ke daftar tunggu, bukan dibiarkan: berkasnya disusun ulang
     * hanya dari rentang terpilih, jadi kalau penandanya tidak dilepas, catatan akan mengaku
     * mereka ada di dalam berkas padahal isinya sudah tidak memuat mereka.
     */
    @Query("select r from GlExportRequest r where r.fileName = :file"
         + " and (r.decidedAt is null or r.decidedAt < :from or r.decidedAt >= :until)"
         + " order by r.id asc")
    List<GlExportRequest> findInFileOutsideRange(@Param("file") String file,
                                                 @Param("from") LocalDateTime from,
                                                 @Param("until") LocalDateTime until);

    /**
     * Isi berkas untuk tombol "Generate Hari Ini" (BUG_AUSY_00174): export yang disetujui
     * HARI INI dan masih menunggu, ditambah seluruh penghuni berkas itu sendiri.
     *
     * Penghuni berkas wajib ikut. Berkasnya selalu disusun ulang dari nol, jadi export yang
     * tidak masuk daftar akan lenyap dari isinya — termasuk yang baru saja dimasukkan lewat
     * tombol rentang tanggal (BUG_AUSY_00169).
     */
    @Query("select r from GlExportRequest r where r.status = 'APPROVED'"
         + " and (r.fileName = :file"
         + "      or (r.fileName is null and r.decidedAt >= :from and r.decidedAt < :until))"
         + " order by r.id asc")
    List<GlExportRequest> findForFileOrApprovedBetween(@Param("file") String file,
                                                       @Param("from") LocalDateTime from,
                                                       @Param("until") LocalDateTime until);
}
