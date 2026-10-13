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
     * Export yang ikut SATU PENULISAN berkas ACCTXT — satu baris export_transfer, bukan satu
     * nama berkas. Dipakai tombol Susun Ulang untuk mengambil isi berkas tanggal tertentu pada
     * versi terakhirnya (BUG_AUSY_00178). Urut id supaya susunan barisnya tetap.
     */
    List<GlExportRequest> findByTransferIdOrderById(Long transferId);

    /** Export yang disetujui pada rentang waktu dan BELUM pernah ikut berkas mana pun. */
    @Query("select r from GlExportRequest r where r.status = 'APPROVED' and r.fileName is null"
         + " and r.decidedAt >= :from and r.decidedAt < :until order by r.id asc")
    List<GlExportRequest> findApprovedNotGeneratedBetween(@Param("from") LocalDateTime from,
                                                          @Param("until") LocalDateTime until);


    /**
     * Kunci antar-PROSES untuk penyusunan berkas ACCTXT (REQ_AUSY_00058).
     *
     * Kunci di dalam aplikasi hanya berlaku satu JVM. Begitu berkasnya juga bisa disusun oleh
     * proses lain — JAR mode tugas yang dipanggil crontab — kunci itu tidak berarti apa-apa:
     * dua proses sama-sama membaca "belum digenerate", sama-sama menulis ACCTXT tanggal itu,
     * dan yang belakangan menimpa yang duluan tanpa satu pun pesan.
     *
     * Dipakai versi _xact_: kuncinya terlepas sendiri saat transaksi selesai, baik commit
     * maupun rollback. Kunci yang harus dilepas manual akan menggantung selamanya kalau
     * prosesnya mati di tengah jalan.
     *
     * @return true kalau kunci didapat; false berarti ada proses lain yang sedang menyusun.
     */
    @Query(value = "select pg_try_advisory_xact_lock(:kunci)", nativeQuery = true)
    boolean kunciPenyusunan(@Param("kunci") long kunci);
}
