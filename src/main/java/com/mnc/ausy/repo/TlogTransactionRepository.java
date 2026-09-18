package com.mnc.ausy.repo;

import com.mnc.ausy.domain.TlogTransaction;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.Collection;
import java.util.List;

public interface TlogTransactionRepository extends JpaRepository<TlogTransaction, Long> {
    List<TlogTransaction> findAllByOrderByTimeActivityDesc(Pageable page);

    /** Transactions performed by users whose group is in {@code groups} (role-scoped audit). */
    @Query(value = "select t.* from tlog_transaction t join users u on u.user_id = t.user_id " +
                   "where u.id_group in (:groups) order by t.time_activity desc", nativeQuery = true)
    List<TlogTransaction> findByUserGroups(@Param("groups") Collection<Integer> groups, Pageable page);

    /**
     * Jejak milik user tertentu saja — dipakai checker, yang hanya boleh melihat aktivitas maker
     * pasangannya (BUG_AUSY_00149), bukan seluruh maker di sisi operasional.
     */
    @Query(value = "select t.* from tlog_transaction t where t.user_id in (:users) order by t.time_activity desc",
           nativeQuery = true)
    List<TlogTransaction> findByUserIds(@Param("users") Collection<String> users, Pageable page);

    /** A maker's full GL history: their own upload/add/edit/delete requests + the checker's
     *  approve/reject decisions on those requests. */
    @Query(value = "select t.* from tlog_transaction t where " +
            "(t.user_id = :maker and t.name_activity in ('UPLOAD','GL_CHANGE_REQ','EXPORT_REQ')) or " +
            "(t.name_activity in ('OTORISASI_APPROVE','OTORISASI_REJECT','GL_CHANGE_APPROVE','GL_CHANGE_REJECT'," +
            " 'EXPORT_APPROVE','EXPORT_REJECT') " +
            " and t.tinfo like :makerLike) " +
            "order by t.time_activity desc", nativeQuery = true)
    List<TlogTransaction> glHistoryForMaker(@Param("maker") String maker, @Param("makerLike") String makerLike, Pageable page);

    /** Transactions whose activity name starts with a prefix, e.g. 'CONFIG_CHANGE%' for the user/config history. */
    @Query(value = "select t.* from tlog_transaction t where t.name_activity like :prefix " +
                   "order by t.time_activity desc", nativeQuery = true)
    List<TlogTransaction> byActivityPrefix(@Param("prefix") String prefix, Pageable page);

    @Query(value = "select to_char(date_trunc('day',time_activity),'DD Mon') as d, count(*) as c " +
                   "from tlog_transaction where time_activity >= (current_date - 6) " +
                   "group by date_trunc('day',time_activity) order by date_trunc('day',time_activity)",
           nativeQuery = true)
    List<Object[]> dailyCounts();
}
