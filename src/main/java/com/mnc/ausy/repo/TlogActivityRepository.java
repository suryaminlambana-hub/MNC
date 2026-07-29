package com.mnc.ausy.repo;

import com.mnc.ausy.domain.TlogActivity;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.Collection;
import java.util.List;

public interface TlogActivityRepository extends JpaRepository<TlogActivity, Long> {
    List<TlogActivity> findAllByOrderByTimeActivityDesc(Pageable page);

    /** Login/activity by users whose group is in {@code groups} (role-scoped audit). */
    @Query(value = "select a.* from tlog_activity a join users u on u.user_id = a.user_id " +
                   "where u.id_group in (:groups) order by a.time_activity desc", nativeQuery = true)
    List<TlogActivity> findByUserGroups(@Param("groups") Collection<Integer> groups, Pageable page);
}
