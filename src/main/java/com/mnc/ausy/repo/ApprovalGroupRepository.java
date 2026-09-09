package com.mnc.ausy.repo;

import com.mnc.ausy.domain.ApprovalGroup;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface ApprovalGroupRepository extends JpaRepository<ApprovalGroup, Long> {
    Optional<ApprovalGroup> findByApproverGroupAndTargetGroup(Integer approver, Integer target);
    boolean existsByApproverGroupAndTargetGroup(Integer approver, Integer target);
    /** Is any group configured as checker for {@code target}? */
    boolean existsByTargetGroup(Integer target);
    /** Aturan approval untuk sebuah group — dipakai mengecek approvernya benar-benar ada & aktif. */
    List<ApprovalGroup> findByTargetGroup(Integer target);
    @org.springframework.transaction.annotation.Transactional
    void deleteByApproverGroupOrTargetGroup(Integer approver, Integer target);
}
