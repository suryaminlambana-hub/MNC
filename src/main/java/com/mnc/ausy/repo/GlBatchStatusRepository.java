package com.mnc.ausy.repo;

import com.mnc.ausy.domain.GlBatchStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface GlBatchStatusRepository extends JpaRepository<GlBatchStatus, Long> {
    Optional<GlBatchStatus> findByNoPaketAndMaker(String noPaket, String maker);
}
