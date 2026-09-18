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

    /**
     * Recap of GL that has passed otorisasi (active & not PENDING) — konsisten dengan List GL.
     * Data yang ditambahkan manual dikelompokkan terpisah dengan penanda {@code (manual)} supaya
     * tidak terbaca sebagai batch hasil upload (BUG_AUSY_00126).
     */
    @Query(value = "select case when coalesce(glsrc,'p')='m' or coalesce(no_paket,'')='' then '(manual)' " +
                   "            else no_paket end as batch, gltrus, count(*), " +
                   "coalesce(sum(case when gltrcd='D' then gltram else 0 end),0), " +
                   "coalesce(sum(case when gltrcd='C' then gltram else 0 end),0) " +
                   "from gl_tabel where active=1 and (status_dual is null or status_dual <> 'PENDING') " +
                   "group by 1, gltrus order by 1", nativeQuery = true)
    List<Object[]> approvedPakets();

    /**
     * Batch yang boleh dihapus di halaman Hapus Batch: hanya hasil upload. Data yang ditambahkan
     * manual bukan bagian dari batch mana pun, jadi tidak boleh ikut terhapus sekaligus
     * (BUG_AUSY_00126) — penghapusannya lewat Kelola Data GL, satu per satu.
     */
    @Query(value = "select no_paket, gltrus, count(*), " +
                   "coalesce(sum(case when gltrcd='D' then gltram else 0 end),0), " +
                   "coalesce(sum(case when gltrcd='C' then gltram else 0 end),0) " +
                   "from gl_tabel where active=1 and (status_dual is null or status_dual <> 'PENDING') " +
                   "and coalesce(glsrc,'p') <> 'm' and coalesce(no_paket,'') <> '' " +
                   "group by no_paket, gltrus order by no_paket", nativeQuery = true)
    List<Object[]> deletablePakets();

    List<GlTabel> findByNoPaketAndGltrusOrderById(String paket, String inputer);

    /** Baris yang benar-benar milik sebuah batch — data tambahan manual tidak ikut (BUG_AUSY_00126). */
    @Query("select g from GlTabel g where g.noPaket=:p and g.gltrus=:u and coalesce(g.glsrc,'p') <> 'm' order by g.id")
    List<GlTabel> findBatchRows(@Param("p") String paket, @Param("u") String inputer);
    List<GlTabel> findByNoPaketOrderById(String paket);
    List<GlTabel> findTop200ByActiveOrderByIdDesc(Short active);
    long countByActive(Short active);

    /** Idem, dibatasi ke maker tertentu — card dashboard yang pandangannya dibatasi (BUG_AUSY_00138). */
    @Query("select count(g) from GlTabel g where g.active=:active and g.gltrus in :makers")
    long countByActiveForMakers(@Param("active") Short active, @Param("makers") java.util.Collection<String> makers);

    // "GL Aktif" excludes rows still awaiting checker otorisasi (statusDual = PENDING)
    List<GlTabel> findTop200ByActiveAndStatusDualNotOrderByIdDesc(Short active, String statusDual);
    long countByActiveAndStatusDualNot(Short active, String statusDual);

    /** Idem, dibatasi ke maker tertentu — beranda maker/checker (BUG_AUSY_00138/00139). */
    @Query("select count(g) from GlTabel g where g.active=:active"
         + " and (g.statusDual is null or g.statusDual <> :statusDual) and g.gltrus in :makers")
    long countActiveNotPendingForMakers(@Param("active") Short active,
                                        @Param("statusDual") String statusDual,
                                        @Param("makers") java.util.Collection<String> makers);

    // how many GL rows still exist for a paket (0 = paket sudah tidak ada -> pending ADD jadi stale)
    long countByNoPaket(String noPaket);

    @Modifying @Transactional
    @Query("delete from GlTabel g where g.noPaket=:p and g.gltrus=:u and coalesce(g.glsrc,'p') <> 'm'")
    void deletePaket(@Param("p") String paket, @Param("u") String inputer);

    // ===== Export flow (maker Ajukan Export -> checker Otorisasi Export -> FTP) =====
    /** GL aktif yang sudah lolos otorisasi upload tapi BELUM diajukan/diexport, dibatasi paket pilihan maker. */
    @Query("select g from GlTabel g where g.active=1 and g.statusDual='APPROVED' " +
           "and g.statusPrint='BELUM UPLOAD' and g.exportReqId is null and g.noPaket in :pakets order by g.id")
    List<GlTabel> findExportableByNoPaketIn(@Param("pakets") List<String> pakets);

    /**
     * Batch hasil upload yang siap diexport, untuk checklist "pilih batch" milik maker.
     * Baris yang ditambahkan manual (glsrc='m') TIDAK ikut di sini — baris begitu dipilih
     * satu per satu di daftarnya sendiri, karena batch-nya bisa saja kosong sehingga tidak
     * pernah bisa tercentang lewat daftar batch (BUG_AUSY_00124/00125).
     */
    /* Kolom ke-5 (gltrus) ditambahkan supaya daftarnya bisa disaring per maker tanpa mengubah
       urutan kolom yang sudah dipakai template (BUG_AUSY_00138). */
    @Query(value = "select no_paket, count(*), " +
                   "coalesce(sum(case when gltrcd='D' then gltram else 0 end),0), " +
                   "coalesce(sum(case when gltrcd='C' then gltram else 0 end),0), gltrus " +
                   "from gl_tabel where active=1 and status_dual='APPROVED' and status_print='BELUM UPLOAD' " +
                   "and export_req_id is null and coalesce(glsrc,'p') <> 'm' and coalesce(no_paket,'') <> '' " +
                   "group by no_paket, gltrus order by no_paket", nativeQuery = true)
    List<Object[]> exportablePakets();

    /** Idem {@link #countByActiveAndStatusDualAndStatusPrintAndExportReqIdIsNull}, dibatasi satu maker. */
    @Query("select count(g) from GlTabel g where g.active=1 and g.statusDual='APPROVED'"
         + " and g.statusPrint='BELUM UPLOAD' and g.exportReqId is null and g.gltrus in :makers")
    long countExportableForMakers(@Param("makers") java.util.Collection<String> makers);

    /** Baris GL tambahan manual yang siap diexport — dipilih satu per satu oleh maker. */
    @Query("select g from GlTabel g where g.active=1 and g.statusDual='APPROVED'"
         + " and g.statusPrint='BELUM UPLOAD' and g.exportReqId is null"
         + " and (g.glsrc = 'm' or g.noPaket is null or g.noPaket = '') order by g.id")
    List<GlTabel> findExportableManual();

    /** GL aktif siap export berdasarkan id baris (untuk data tambahan manual). */
    @Query("select g from GlTabel g where g.active=1 and g.statusDual='APPROVED'"
         + " and g.statusPrint='BELUM UPLOAD' and g.exportReqId is null and g.id in :ids order by g.id")
    List<GlTabel> findExportableByIdIn(@Param("ids") List<Long> ids);

    long countByActiveAndStatusDualAndStatusPrintAndExportReqIdIsNull(Short active, String statusDual, String statusPrint);

    List<GlTabel> findByExportReqIdOrderById(Long exportReqId);

    /** Tandai baris exportable ke sebuah request (dipanggil saat maker Ajukan Export). */
    @Modifying @Transactional
    @Query("update GlTabel g set g.exportReqId=:rid, g.statusPrint='PROSES EXPORT' " +
           "where g.active=1 and g.statusDual='APPROVED' and g.statusPrint='BELUM UPLOAD' and g.exportReqId is null " +
           "and g.noPaket in :pakets")
    int markExportable(@Param("rid") Long reqId, @Param("pakets") List<String> pakets);

    /** Idem untuk baris yang dipilih satu per satu. */
    @Modifying @Transactional
    @Query("update GlTabel g set g.exportReqId=:rid, g.statusPrint='PROSES EXPORT' " +
           "where g.active=1 and g.statusDual='APPROVED' and g.statusPrint='BELUM UPLOAD' and g.exportReqId is null " +
           "and g.id in :ids")
    int markExportableByIds(@Param("rid") Long reqId, @Param("ids") List<Long> ids);

    /** Approve export: baris request jadi SUDAH UPLOAD (terkirim ke core). */
    @Modifying @Transactional
    @Query("update GlTabel g set g.statusPrint='SUDAH UPLOAD' where g.exportReqId=:rid")
    int markExported(@Param("rid") Long reqId);

    /** Reject export: kembalikan baris agar bisa diajukan lagi. */
    @Modifying @Transactional
    @Query("update GlTabel g set g.statusPrint='BELUM UPLOAD', g.exportReqId=null where g.exportReqId=:rid")
    int revertExport(@Param("rid") Long reqId);
}
