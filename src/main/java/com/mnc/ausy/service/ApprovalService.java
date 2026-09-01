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

    /**
     * May a user in {@code approverGroup} approve work submitted by {@code submitterUserId}?
     * Strictly driven by the approval_group matrix (no super-approver bypass) — enforces
     * segregation of duties: an approver can only approve groups explicitly assigned to it,
     * and never its own submissions.
     */
    public boolean canApprove(Integer approverGroup, String submitterUserId) {
        Integer targetGroup = users.findByUserId(submitterUserId).map(u -> u.getIdGroup()).orElse(null);
        if (approverGroup == null || targetGroup == null) return false;
        if (approverGroup.equals(targetGroup)) return false;   // cannot approve own group
        return rules.existsByApproverGroupAndTargetGroup(approverGroup, targetGroup);
    }

    /**
     * Must a change made by a user in {@code makerGroup} wait for a Checker?
     * True when the approval_group matrix names some group as checker for it — so who needs
     * approval follows the Approval Group screen, never a hard-coded role list.
     */
    public boolean requiresApproval(Integer makerGroup) {
        return makerGroup != null && rules.existsByTargetGroup(makerGroup);
    }
}
