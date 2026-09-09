package com.mnc.ausy.service;

import com.mnc.ausy.domain.ApprovalGroup;
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

    /** Pesan baku ketika group pengaju belum punya penyetuju yang aktif (BUG_AUSY_00116). */
    public static final String NO_APPROVER =
            "Pengajuan tidak dapat diproses karena Approval Group Anda belum memiliki Checker/Approver"
          + " yang aktif. Silakan hubungi Administrator.";

    /**
     * Apakah group ini punya penyetuju yang benar-benar bisa memutuskan: ada aturan di Approval
     * Group DAN group penyetujunya punya minimal satu user aktif. Tanpa itu pengajuan hanya akan
     * menggantung tanpa ada yang bisa approve — atau, kalau tidak ada aturan sama sekali,
     * perubahan malah langsung diterapkan tanpa dual control (BUG_AUSY_00116).
     */
    public boolean hasActiveApprover(Integer makerGroup) {
        if (makerGroup == null) return false;
        for (ApprovalGroup r : rules.findByTargetGroup(makerGroup)) {
            Integer approver = r.getApproverGroup();
            if (approver == null || approver.equals(makerGroup)) continue;
            if (users.countByIdGroupAndStatusAccount(approver, (short) 1) > 0) return true;
        }
        return false;
    }

    /** null kalau boleh mengajukan; kalau tidak, alasannya untuk ditampilkan ke pengaju. */
    public String submissionBlockedReason(Integer makerGroup) {
        return hasActiveApprover(makerGroup) ? null : NO_APPROVER;
    }
}
