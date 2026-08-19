package com.mnc.ausy.repo;

import com.mnc.ausy.domain.FtpConfig;
import org.springframework.data.jpa.repository.JpaRepository;

public interface FtpConfigRepository extends JpaRepository<FtpConfig, Integer> {
}
