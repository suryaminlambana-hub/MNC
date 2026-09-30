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
}
