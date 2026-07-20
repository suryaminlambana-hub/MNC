package com.mnc.ausy.repo;

import com.mnc.ausy.domain.GlChangeRequest;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface GlChangeRequestRepository extends JpaRepository<GlChangeRequest, Long> {
    List<GlChangeRequest> findByStatusOrderByCreatedAtDesc(String status);
    List<GlChangeRequest> findByMakerOrderByCreatedAtDesc(String maker);
    long countByStatus(String status);
}
