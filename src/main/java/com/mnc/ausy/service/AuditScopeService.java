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

    public AuditScopeService(GroupUserRepository groups, AccessService access) {
        this.groups = groups; this.access = access;
    }

    /**
     * Group yang jejaknya boleh dilihat group ini. Kalau sisinya tidak bisa ditentukan (memegang
     * menu kedua sisi, atau belum diberi menu apa pun) yang tampil hanya jejak group itu sendiri —
     * lebih baik terlalu sempit daripada membocorkan log sisi lain.
     */
    public List<Integer> visibleGroups(Integer idGroup) {
        List<Integer> out = new ArrayList<>();
        if (idGroup == null) return out;
        String side = access.sideOf(idGroup);
        if (side == null) { out.add(idGroup); return out; }
        for (GroupUser g : groups.findAll())
            if (side.equals(access.sideOf(g.getIdGroup()))) out.add(g.getIdGroup());
        if (!out.contains(idGroup)) out.add(idGroup);
        return out;
    }

    /** Label sisi untuk ditampilkan di halaman audit. */
    public String label(Integer idGroup) {
        String side = access.sideOf(idGroup);
        if ("CONFIG".equals(side)) return "Admin & Admin Approval";
        if ("BUSINESS".equals(side)) return "Maker & Checker";
        return "group Anda sendiri";
    }
}
