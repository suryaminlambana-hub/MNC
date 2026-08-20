package com.mnc.ausy.repo;

import com.mnc.ausy.domain.GlExportRequest;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface GlExportRequestRepository extends JpaRepository<GlExportRequest, Long> {
    List<GlExportRequest> findByStatusOrderByCreatedAtDesc(String status);
    long countByStatus(String status);
    List<GlExportRequest> findByRequestedByAndStatusOrderByCreatedAtDesc(String requestedBy, String status);
}
