package com.mnc.ausy.repo;

import com.mnc.ausy.domain.GlCode;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface GlCodeRepository extends JpaRepository<GlCode, Long> {
    List<GlCode> findAllByOrderByCodeAscPositionAsc();
    Optional<GlCode> findByCodeAndPosition(String code, String position);
    boolean existsByCodeAndPosition(String code, String position);
}
