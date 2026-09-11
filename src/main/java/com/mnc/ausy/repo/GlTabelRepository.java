package com.mnc.ausy.repo;

import com.mnc.ausy.domain.GlTabel;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.util.List;

public interface GlTabelRepository extends JpaRepository<GlTabel, Long> {
    /**
     * Berapa baris GL yang memakai kode ini pada sisi D/C tersebut — baris yang sudah dihapus
     * (active=0) TIDAK dihitung, karena datanya sudah tidak ada di sistem dan dulu membuat kode
     * baru ikut tertulis "Terpakai" padahal tidak ada datanya (BUG_AUSY_00123).
     * 100-D terpakai tidak berarti 100-C ikut terpakai (BUG_AUSY_00100).
     */
    @Query("select count(g) from GlTabel g where g.gltrac = :code and upper(g.gltrcd) = :pos and g.active = 1")
    long countUsage(@Param("code") String code, @Param("pos") String pos);

    /** Dari pemakaian itu, berapa yang batch-nya masih menunggu otorisasi upload. */
    @Query("select count(g) from GlTabel g where g.gltrac = :code and upper(g.gltrcd) = :pos"
         + " and g.active = 1 and g.statusDual = 'PENDING'")
    long countUsagePending(@Param("code") String code, @Param("pos") String pos);

    // Hanya cast baris yang no_paket-nya angka murni; hindari error "invalid input syntax
    // for type integer" saat ada no_paket kosong/non-numerik (BUG_00069).
    @Query(value = "select coalesce(max(no_paket::int),0) from gl_tabel where no_paket ~ '^[0-9]+$'", nativeQuery = true)
    int maxPaket();

    @Query(value = "select coalesce(sum(gltram),0) from gl_tabel where no_paket=:p and gltrcd=:dc", nativeQuery = true)
    BigDecimal sumByPaketDc(@Param("p") String paket, @Param("dc") String dc);

    @Query(value = "select no_paket, gltrus, count(*), " +
                   "coalesce(sum(case when gltrcd='D' then gltram else 0 end),0), " +
                   "coalesce(sum(case when gltrcd='C' then gltram else 0 end),0), " +
                   "max(adddt) " +
                   "from gl_tabel where status_print='BELUM UPLOAD' and status_dual='PENDING' " +
                   "group by no_paket, gltrus order by no_paket", nativeQuery = true)
    List<Object[]> pendingPakets();

    /** Recap of GL that has passed otorisasi (active & not PENDING) — konsisten dengan List GL. */
    @Query(value = "select no_paket, gltrus, count(*), " +
                   "coalesce(sum(case when gltrcd='D' then gltram else 0 end),0), " +
                   "coalesce(sum(case when gltrcd='C' then gltram else 0 end),0) " +
                   "from gl_tabel where active=1 and (status_dual is null or status_dual <> 'PENDING') " +
                   "group by no_paket, gltrus order by no_paket", nativeQuery = true)
    List<Object[]> approvedPakets();

    List<GlTabel> findByNoPaketAndGltrusOrderById(String paket, String inputer);
    List<GlTabel> findByNoPaketOrderById(String paket);
    List<GlTabel> findTop200ByActiveOrderByIdDesc(Short active);
    long countByActive(Short active);

    // "GL Aktif" excludes rows still awaiting checker otorisasi (statusDual = PENDING)
    List<GlTabel> findTop200ByActiveAndStatusDualNotOrderByIdDesc(Short active, String statusDual);
    long countByActiveAndStatusDualNot(Short active, String statusDual);

    // how many GL rows still exist for a paket (0 = paket sudah tidak ada -> pending ADD jadi stale)
    long countByNoPaket(String noPaket);

    @Modifying @Transactional
    @Query("delete from GlTabel g where g.noPaket=:p and g.gltrus=:u")
    void deletePaket(@Param("p") String paket, @Param("u") String inputer);

    // ===== Export flow (maker Ajukan Export -> checker Otorisasi Export -> FTP) =====
    /** GL aktif yang sudah lolos otorisasi upload tapi BELUM diajukan/diexport, dibatasi paket pilihan maker. */
    @Query("select g from GlTabel g where g.active=1 and g.statusDual='APPROVED' " +
           "and g.statusPrint='BELUM UPLOAD' and g.exportReqId is null and g.noPaket in :pakets order by g.id")
    List<GlTabel> findExportableByNoPaketIn(@Param("pakets") List<String> pakets);

    /** Exportable rows grouped by paket, for the maker's "pilih paket" checklist. */
    @Query(value = "select no_paket, count(*), " +
                   "coalesce(sum(case when gltrcd='D' then gltram else 0 end),0), " +
                   "coalesce(sum(case when gltrcd='C' then gltram else 0 end),0) " +
                   "from gl_tabel where active=1 and status_dual='APPROVED' and status_print='BELUM UPLOAD' " +
                   "and export_req_id is null group by no_paket order by no_paket", nativeQuery = true)
    List<Object[]> exportablePakets();

    long countByActiveAndStatusDualAndStatusPrintAndExportReqIdIsNull(Short active, String statusDual, String statusPrint);

    List<GlTabel> findByExportReqIdOrderById(Long exportReqId);

    /** Tandai baris exportable ke sebuah request (dipanggil saat maker Ajukan Export). */
    @Modifying @Transactional
    @Query("update GlTabel g set g.exportReqId=:rid, g.statusPrint='PROSES EXPORT' " +
           "where g.active=1 and g.statusDual='APPROVED' and g.statusPrint='BELUM UPLOAD' and g.exportReqId is null " +
           "and g.noPaket in :pakets")
    int markExportable(@Param("rid") Long reqId, @Param("pakets") List<String> pakets);

    /** Approve export: baris request jadi SUDAH UPLOAD (terkirim ke core). */
    @Modifying @Transactional
    @Query("update GlTabel g set g.statusPrint='SUDAH UPLOAD' where g.exportReqId=:rid")
    int markExported(@Param("rid") Long reqId);

    /** Reject export: kembalikan baris agar bisa diajukan lagi. */
    @Modifying @Transactional
    @Query("update GlTabel g set g.statusPrint='BELUM UPLOAD', g.exportReqId=null where g.exportReqId=:rid")
    int revertExport(@Param("rid") Long reqId);
}
