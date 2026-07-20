package com.mnc.ausy.repo;

import com.mnc.ausy.domain.TlogTransaction;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import java.util.List;

public interface TlogTransactionRepository extends JpaRepository<TlogTransaction, Long> {
    List<TlogTransaction> findAllByOrderByTimeActivityDesc(Pageable page);

    @Query(value = "select to_char(date_trunc('day',time_activity),'DD Mon') as d, count(*) as c " +
                   "from tlog_transaction where time_activity >= (current_date - 6) " +
                   "group by date_trunc('day',time_activity) order by date_trunc('day',time_activity)",
           nativeQuery = true)
    List<Object[]> dailyCounts();
}
