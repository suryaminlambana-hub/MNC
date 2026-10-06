package com.mnc.ausy.service;

import com.mnc.ausy.domain.GroupUser;
import com.mnc.ausy.repo.GroupUserRepository;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * Siapa saja yang jejaknya boleh dilihat seseorang di audit trail (BUG_AUSY_00115).
 *
 * Pemisahannya tegas: yang bekerja di sisi operasional hanya melihat jejak Maker &amp; Checker,
 * yang di sisi administratif hanya melihat jejak Admin &amp; Admin Approval. Sisi sebuah group
 * dibaca dari menu yang dipegangnya ({@link AccessService#sideOf}), jadi group buatan admin ikut
 * tersaring tanpa perlu menulis id group di kode.
 *
 * Dipakai bersama oleh halaman Audit Trail, export-nya, dan card "Aktivitas Terbaru" di
 * dashboard — supaya tidak ada satu pun jalan yang menampilkan log gabungan.
 */
@Service
public class AuditScopeService {

    private final GroupUserRepository groups;
    private final AccessService access;
    private final ApprovalService approval;

    public AuditScopeService(GroupUserRepository groups, AccessService access, ApprovalService approval) {
        this.groups = groups; this.access = access; this.approval = approval;
    }

    public static final String BUSINESS = "BUSINESS";   // jejak Maker & Checker
    public static final String CONFIG    = "CONFIG";    // jejak Admin & Admin Approval

    /**
     * Group yang termasuk sisi tertentu. Sisinya ditentukan oleh halaman yang dibuka
     * (Audit Trail operasional vs administratif), jadi isinya pasti — tidak lagi ditebak dari
     * menu lain yang kebetulan dipegang si pembaca.
     */
    public List<Integer> groupsOnSide(String side) {
        List<Integer> out = new ArrayList<>();
        for (GroupUser g : groups.findAll())
            if (side != null && side.equals(access.sideOf(g.getIdGroup()))) out.add(g.getIdGroup());
        if (out.isEmpty()) out.add(-1);          // jangan pernah jatuh ke "tanpa filter"
        return out;
    }

    /**
     * Jejak yang boleh dilihat SEORANG penyetuju: group-nya sendiri + group yang memang
     * diotorisasinya. Checker tidak lagi melihat aktivitas maker di luar naungannya
     * (BUG_AUSY_00149); yang menentukan tetap matriks Approval Group, bukan "satu sisi".
     */
    public List<Integer> groupsVisibleTo(Integer viewerGroup, String side) {
        List<Integer> out = new ArrayList<>();
        if (viewerGroup == null) { out.add(-1); return out; }
        out.add(viewerGroup);
        for (Integer g : groupsOnSide(side)) {
            if (g == null || g.equals(viewerGroup) || out.contains(g)) continue;
            boolean mine = CONFIG.equals(side)
                    ? approval.canApproveConfigGroup(viewerGroup, g)
                    : approval.canApproveGroup(viewerGroup, g);
            if (mine) out.add(g);
        }
        return out;
    }

    /** Label sisi untuk ditampilkan di halaman audit. */
    public String label(String side) {
        return CONFIG.equals(side) ? "Admin & Admin Approval" : "Maker & Checker";
    }
}
