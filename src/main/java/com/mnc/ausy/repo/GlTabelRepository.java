package com.mnc.ausy.repo;

import com.mnc.ausy.domain.GlTabel;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.util.Collection;
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
    // how many GL rows still exist for a paket (0 = paket sudah tidak ada -> pending ADD jadi stale)
    long countByNoPaket(String noPaket);

    @Modifying @Transactional
    @Query("delete from GlTabel g where g.noPaket=:p and g.gltrus=:u and coalesce(g.glsrc,'p') <> 'm'")
    void deletePaket(@Param("p") String paket, @Param("u") String inputer);

    // ===== Export flow (maker Ajukan Export -> checker Otorisasi Export -> file ACCTXT) =====
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

    // ===== List GL Aktif: dipaginasi di database, tidak lagi memuat seluruh tabel =====
    /** Halaman GL aktif (sudah lolos otorisasi) — tanpa batas maker. */
    @Query("select g from GlTabel g where g.active=1 and (g.statusDual is null or g.statusDual <> 'PENDING')")
    Page<GlTabel> pageActive(Pageable page);

    /** Halaman GL aktif milik maker tertentu saja (batas per maker/checker). */
    @Query("select g from GlTabel g where g.active=1 and (g.statusDual is null or g.statusDual <> 'PENDING')"
         + " and g.gltrus in :makers")
    Page<GlTabel> pageActiveByMakers(@Param("makers") Collection<String> makers, Pageable page);

    /**
     * "GL Aktif" dengan batas per maker sekaligus — SATU pintu untuk semua layar (BUG_AUSY_00150).
     *
     * Sebelumnya tiap layar menulis definisinya sendiri dan keduanya menyimpang: List GL Aktif
     * menyaring di Java dengan {@code !"PENDING".equals(statusDual)} (baris ber-status_dual NULL
     * ikut tampil), sementara Kelola Data GL memakai query turunan {@code ...AndStatusDualNot...}
     * yang menjadi {@code status_dual <> 'PENDING'} — di SQL, {@code NULL <> 'PENDING'} bernilai
     * UNKNOWN, jadi baris NULL hilang diam-diam. Kelola Data GL juga berhenti di 200 baris
     * sementara List GL Aktif tidak. Keduanya sekarang lewat sini.
     *
     * {@code makers} null = tanpa batas; kosong = memang tidak ada yang boleh dilihat.
     */
    default Page<GlTabel> pageGlAktif(Collection<String> makers, Pageable page) {
        if (makers == null) return pageActive(page);
        if (makers.isEmpty()) return Page.empty(page);
        return pageActiveByMakers(makers, page);
    }

    /**
     * Seluruh baris GL Aktif sekaligus — hanya untuk unduhan (PDF / Excel bulk edit) yang memang
     * harus memuat semuanya. Layar tetap memakai {@link #pageGlAktif} supaya tidak merender
     * belasan ribu baris (BUG_AUSY_00147).
     */
    default List<GlTabel> findGlAktifAll(Collection<String> makers) {
        return pageGlAktif(makers, Pageable.unpaged(Sort.by(Sort.Direction.DESC, "id"))).getContent();
    }

    /** Ringkasan seluruh GL aktif: jumlah baris, total nominal, dan berapa yang kodenya terdaftar. */
    @Query("select count(g), coalesce(sum(g.gltram),0),"
         + " sum(case when exists (select 1 from GlCode c where c.code = g.gltrac"
         + "   and upper(c.position) = upper(g.gltrcd)) then 1 else 0 end)"
         + " from GlTabel g where g.active=1 and (g.statusDual is null or g.statusDual <> 'PENDING')")
    Object[] activeSummary();

    /** Idem, dibatasi maker tertentu. */
    @Query("select count(g), coalesce(sum(g.gltram),0),"
         + " sum(case when exists (select 1 from GlCode c where c.code = g.gltrac"
         + "   and upper(c.position) = upper(g.gltrcd)) then 1 else 0 end)"
         + " from GlTabel g where g.active=1 and (g.statusDual is null or g.statusDual <> 'PENDING')"
         + " and g.gltrus in :makers")
    Object[] activeSummaryByMakers(@Param("makers") Collection<String> makers);

    /**
     * Ringkasan (jumlah baris, total debit, total credit) baris siap export untuk batch pilihan —
     * dihitung di database. Dipakai saat maker mengajukan export supaya tidak perlu memuat
     * belasan ribu baris ke memori (BUG_AUSY_00148).
     */
    @Query("select count(g), coalesce(sum(case when upper(g.gltrcd)='D' then g.gltram else 0 end),0),"
         + " coalesce(sum(case when upper(g.gltrcd)='C' then g.gltram else 0 end),0)"
         + " from GlTabel g where g.active=1 and g.statusDual='APPROVED' and g.statusPrint='BELUM UPLOAD'"
         + " and g.exportReqId is null and g.noPaket in :pakets")
    Object[] exportableSummaryByPaket(@Param("pakets") List<String> pakets);

    /** Idem untuk baris yang dipilih satu per satu. */
    @Query("select count(g), coalesce(sum(case when upper(g.gltrcd)='D' then g.gltram else 0 end),0),"
         + " coalesce(sum(case when upper(g.gltrcd)='C' then g.gltram else 0 end),0)"
         + " from GlTabel g where g.active=1 and g.statusDual='APPROVED' and g.statusPrint='BELUM UPLOAD'"
         + " and g.exportReqId is null and g.id in :ids")
    Object[] exportableSummaryByIds(@Param("ids") List<Long> ids);

    /** Uploader berbeda yang muncul pada baris siap export dari batch tersebut. */
    @Query("select distinct g.gltrus from GlTabel g where g.active=1 and g.statusDual='APPROVED'"
         + " and g.statusPrint='BELUM UPLOAD' and g.exportReqId is null and g.noPaket in :pakets")
    List<String> exportableUploadersByPaket(@Param("pakets") List<String> pakets);

    /** Idem untuk baris pilihan. */
    @Query("select distinct g.gltrus from GlTabel g where g.active=1 and g.statusDual='APPROVED'"
         + " and g.statusPrint='BELUM UPLOAD' and g.exportReqId is null and g.id in :ids")
    List<String> exportableUploadersByIds(@Param("ids") List<Long> ids);

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

    /**
     * Lepas baris dari berkas ACCTXT tanpa membatalkan permintaan exportnya (BUG_AUSY_00169).
     *
     * Dipakai ketika admin menyusun ulang berkas hari ini untuk rentang tanggal yang lebih
     * sempit: export yang jatuh di luar rentang keluar dari berkas dan kembali menunggu, jadi
     * statusnya tidak boleh tetap "SUDAH UPLOAD". Bedanya dengan {@link #revertExport} —
     * {@code export_req_id} dibiarkan, karena permintaan exportnya sendiri tetap sah dan sudah
     * disetujui checker; yang batal cuma keikutsertaannya pada berkas.
     */
    @Modifying @Transactional
    @Query("update GlTabel g set g.statusPrint='BELUM UPLOAD' where g.exportReqId=:rid")
    int unmarkExported(@Param("rid") Long reqId);

    /** Reject export: kembalikan baris agar bisa diajukan lagi. */
    @Modifying @Transactional
    @Query("update GlTabel g set g.statusPrint='BELUM UPLOAD', g.exportReqId=null where g.exportReqId=:rid")
    int revertExport(@Param("rid") Long reqId);
}
