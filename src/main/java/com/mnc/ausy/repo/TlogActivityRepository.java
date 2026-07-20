package com.mnc.ausy.repo;

import com.mnc.ausy.domain.TlogActivity;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface TlogActivityRepository extends JpaRepository<TlogActivity, Long> {
    List<TlogActivity> findAllByOrderByTimeActivityDesc(Pageable page);
}
