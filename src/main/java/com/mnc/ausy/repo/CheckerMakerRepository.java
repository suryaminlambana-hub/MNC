package com.mnc.ausy.repo;

import com.mnc.ausy.domain.CheckerMaker;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

public interface CheckerMakerRepository extends JpaRepository<CheckerMaker, Long> {
    List<CheckerMaker> findByChecker(String checker);
    List<CheckerMaker> findByMaker(String maker);
    boolean existsByChecker(String checker);
    boolean existsByMaker(String maker);
    boolean existsByCheckerAndMaker(String checker, String maker);
    @Transactional void deleteByChecker(String checker);
    @Transactional void deleteByMaker(String maker);
}
