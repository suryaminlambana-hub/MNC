package com.mnc.ausy.repo;

import com.mnc.ausy.domain.AcctxtSchedulerLog;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AcctxtSchedulerLogRepository extends JpaRepository<AcctxtSchedulerLog, Long> {
    List<AcctxtSchedulerLog> findAllByOrderByMulaiDesc(Pageable page);
}
