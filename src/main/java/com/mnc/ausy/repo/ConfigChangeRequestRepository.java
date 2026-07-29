package com.mnc.ausy.repo;

import com.mnc.ausy.domain.ConfigChangeRequest;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface ConfigChangeRequestRepository extends JpaRepository<ConfigChangeRequest, Long> {
    List<ConfigChangeRequest> findByStatusOrderByCreatedAtDesc(String status);
    long countByStatus(String status);
}
