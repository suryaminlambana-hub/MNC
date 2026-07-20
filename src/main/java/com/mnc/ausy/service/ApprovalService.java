package com.mnc.ausy.service;

import com.mnc.ausy.repo.ApprovalGroupRepository;
import com.mnc.ausy.repo.UserRepository;
import org.springframework.stereotype.Service;

@Service
public class ApprovalService {
    private final ApprovalGroupRepository rules;
    private final UserRepository users;

    public ApprovalService(ApprovalGroupRepository rules, UserRepository users) {
        this.rules = rules; this.users = users;
    }

    /** May a user in {@code approverGroup} approve GL submitted by {@code submitterUserId}? */
    public boolean canApprove(Integer approverGroup, String submitterUserId) {
        if (approverGroup != null && approverGroup == 1) return true;   // superadmin
        Integer targetGroup = users.findByUserId(submitterUserId).map(u -> u.getIdGroup()).orElse(null);
        if (approverGroup == null || targetGroup == null) return false;
        return rules.existsByApproverGroupAndTargetGroup(approverGroup, targetGroup);
    }
}
