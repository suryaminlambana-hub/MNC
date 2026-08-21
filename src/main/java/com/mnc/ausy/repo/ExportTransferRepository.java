package com.mnc.ausy.repo;

import com.mnc.ausy.domain.ExportTransfer;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface ExportTransferRepository extends JpaRepository<ExportTransfer, Long> {
    List<ExportTransfer> findTop50ByOrderByCreatedAtDesc();
    long countByStatus(String status);
    List<ExportTransfer> findByStatusInOrderByCreatedAtDesc(List<String> statuses);
    List<ExportTransfer> findByInputerAndStatusOrderByCreatedAtDesc(String inputer, String status);
}
