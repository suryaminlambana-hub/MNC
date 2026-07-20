package com.mnc.ausy.repo;

import com.mnc.ausy.domain.TlogTransaction;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface TlogTransactionRepository extends JpaRepository<TlogTransaction, Long> {
    List<TlogTransaction> findAllByOrderByTimeActivityDesc(Pageable page);
}
