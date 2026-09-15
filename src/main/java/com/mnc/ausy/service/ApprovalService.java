package com.mnc.ausy.service;

import com.mnc.ausy.domain.ApprovalGroup;
import com.mnc.ausy.domain.GroupUser;
import com.mnc.ausy.repo.ApprovalGroupRepository;
import com.mnc.ausy.repo.GroupUserRepository;
import com.mnc.ausy.repo.UserRepository;
import org.springframework.stereotype.Service;

@Service
public class ApprovalService {
    private final ApprovalGroupRepository rules;
    private final UserRepository users;
    private final GroupUserRepository groups;
    private final AccessService access;

    public ApprovalService(ApprovalGroupRepository rules, UserRepository users,
                           GroupUserRepository groups, AccessService access) {
        this.rules = rules; this.users = users; this.groups = groups; this.access = access;
    }

    /**
     * Role yang memang bertugas mengotorisasi config — dikenali dari haknya atas aksi
     * "Otorisasi Perubahan Config", bukan dari id group yang ditulis di kode.
     */
    public boolean configAuthorizer(Integer group) {
        return group != null && access.allowed(group, "/config-change")
                && "CONFIG".equals(access.sideOf(group));
    }

    /**
     * Role sisi config (Admin & sejenisnya) selalu boleh diotorisasi oleh role Config Approver,
     * tanpa perlu aturan Approval Group yang ditulis satu per satu — permintaan MNC, supaya
     * mengganti role Config Approver tidak memutus rantai otorisasi (BUG_AUSY_00128).
     */
    private boolean configPairing(Integer approverGroup, Integer targetGroup) {
        return configAuthorizer(approverGroup) && "CONFIG".equals(access.sideOf(targetGroup));
    }

    /**
     * May a user in {@code approverGroup} approve work submitted by {@code submitterUserId}?
     * Dari matriks Approval Group, ditambah satu pasangan bawaan: Config Approver selalu boleh
     * mengotorisasi pengajuan dari role sisi config. Approver tetap tidak pernah bisa menyetujui
     * pengajuan group-nya sendiri.
     */
    public boolean canApprove(Integer approverGroup, String submitterUserId) {
        Integer targetGroup = users.findByUserId(submitterUserId).map(u -> u.getIdGroup()).orElse(null);
        if (approverGroup == null || targetGroup == null) return false;
        if (approverGroup.equals(targetGroup)) return false;   // cannot approve own group
        return rules.existsByApproverGroupAndTargetGroup(approverGroup, targetGroup)
                || configPairing(approverGroup, targetGroup);
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
        // pasangan bawaan sisi config: cukup ada Config Approver ber-user aktif
        if ("CONFIG".equals(access.sideOf(makerGroup)))
            for (GroupUser g : groups.findAll()) {
                Integer id = g.getIdGroup();
                if (id == null || id.equals(makerGroup) || !configAuthorizer(id)) continue;
                if (users.countByIdGroupAndStatusAccount(id, (short) 1) > 0) return true;
            }
        return false;
    }

    /** Apakah role ini punya wewenang menyetujui — lewat aturan Approval Group atau sebagai Config Approver. */
    public boolean isApprover(Integer group) {
        if (group == null) return false;
        if (configAuthorizer(group)) return true;
        for (ApprovalGroup r : rules.findAll()) if (group.equals(r.getApproverGroup())) return true;
        return false;
    }

    /** null kalau boleh mengajukan; kalau tidak, alasannya untuk ditampilkan ke pengaju. */
    public String submissionBlockedReason(Integer makerGroup) {
        return hasActiveApprover(makerGroup) ? null : NO_APPROVER;
    }
}
