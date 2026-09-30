package com.mnc.ausy.repo;

import com.mnc.ausy.domain.GlCode;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

/**
 * Master Kode GL. Sejak BUG_AUSY_00170 sebuah kode dikenali dari NOMORNYA saja — sisi D/C tidak
 * lagi jadi bagian identitasnya, mengikuti daftar COA milik MNC yang juga hanya memuat nomor
 * akun dan namanya.
 */
public interface GlCodeRepository extends JpaRepository<GlCode, Long> {
    List<GlCode> findAllByOrderByCodeAsc();
    Optional<GlCode> findByCode(String code);
    boolean existsByCode(String code);
}
