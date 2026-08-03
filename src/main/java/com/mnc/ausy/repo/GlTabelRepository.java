package com.mnc.ausy.repo;

import com.mnc.ausy.domain.GlTabel;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.util.List;

public interface GlTabelRepository extends JpaRepository<GlTabel, Long> {
    boolean existsByGltrac(String gltrac);

    @Query(value = "select coalesce(max(no_paket::int),0) from gl_tabel", nativeQuery = true)
    int maxPaket();

    @Query(value = "select coalesce(sum(gltram),0) from gl_tabel where no_paket=:p and gltrcd=:dc", nativeQuery = true)
    BigDecimal sumByPaketDc(@Param("p") String paket, @Param("dc") String dc);

    @Query(value = "select no_paket, gltrus, count(*), " +
                   "coalesce(sum(case when gltrcd='D' then gltram else 0 end),0), " +
                   "coalesce(sum(case when gltrcd='C' then gltram else 0 end),0) " +
                   "from gl_tabel where status_print='BELUM UPLOAD' " +
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
}
