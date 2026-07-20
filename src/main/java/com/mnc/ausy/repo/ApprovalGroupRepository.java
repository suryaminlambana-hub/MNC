package com.mnc.ausy.repo;

import com.mnc.ausy.domain.ApprovalGroup;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;

public interface ApprovalGroupRepository extends JpaRepository<ApprovalGroup, Long> {
    Optional<ApprovalGroup> findByApproverGroupAndTargetGroup(Integer approver, Integer target);
    boolean existsByApproverGroupAndTargetGroup(Integer approver, Integer target);
}
