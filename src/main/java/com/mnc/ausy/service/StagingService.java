package com.mnc.ausy.service;

import com.mnc.ausy.domain.GlExportRequest;
import com.mnc.ausy.domain.GlTabel;
import com.mnc.ausy.domain.User;
import com.mnc.ausy.repo.GlExportRequestRepository;
import com.mnc.ausy.repo.GlTabelRepository;
import com.mnc.ausy.repo.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Penampungan data GL untuk role EXECUTE (REQ_AUSY_00038).
 *
 * Alur lama: maker upload → checker approve → maker ajukan export → checker approve export →
 * Execute generate. Alur baru memangkas dua langkah tengah: begitu checker meng-approve upload
 * (atau perubahan Kelola Data GL), datanya langsung masuk penampungan.
 *
 * "Penampungan" sengaja memakai mekanisme yang sudah ada — {@link GlExportRequest} berstatus
 * APPROVED yang belum punya file — karena itulah yang dibaca halaman Generate File ACCTXT
 * sebagai "Menunggu digenerate". Dengan begitu penyusunan berkas ACCTXT, pengiriman FTP, Susun
 * Ulang, dan tanda "sudah digenerate" (anti generate ganda) tidak berubah sama sekali.
 */
@Service
public class StagingService {

    private final GlExportRequestRepository requests;
    private final GlTabelRepository gl;
    private final UserRepository users;

    public StagingService(GlExportRequestRepository requests, GlTabelRepository gl, UserRepository users) {
        this.requests = requests; this.gl = gl; this.users = users;
    }

    /**
     * Masukkan baris yang sudah di-approve ke penampungan: satu catatan penampungan untuk
     * kelompok baris ini (satu batch upload, atau satu data manual), dan tiap baris ditautkan ke
     * catatan itu. Baris yang sudah ada di penampungan tidak dimasukkan lagi.
     */
    @Transactional
    public GlExportRequest stage(List<GlTabel> rows, String maker, String checker, String asal) {
        List<GlTabel> masuk = rows.stream().filter(g -> g.getExportReqId() == null).toList();
        if (masuk.isEmpty()) return null;

        BigDecimal d = BigDecimal.ZERO, c = BigDecimal.ZERO;
        for (GlTabel g : masuk) {
            BigDecimal a = g.getGltram() == null ? BigDecimal.ZERO : g.getGltram();
            if ("C".equalsIgnoreCase(g.getGltrcd())) c = c.add(a); else d = d.add(a);
        }
        LocalDateTime now = LocalDateTime.now();
        GlExportRequest req = new GlExportRequest();
        req.setRequestedBy(maker);
        req.setRequesterGroup(users.findByUserId(maker).map(User::getIdGroup).orElse(null));
        req.setStatus("APPROVED");
        req.setRowsCount(masuk.size());
        req.setDebit(d);
        req.setCredit(c);
        req.setCreatedAt(now);
        req.setNoteMaker("Otomatis masuk penampungan: " + asal);
        req.setChecker(checker);
        req.setDecidedAt(now);
        requests.save(req);

        for (GlTabel g : masuk) {
            g.setExportReqId(req.getId());
            g.setStatusPrint("PROSES EXPORT");
        }
        gl.saveAll(masuk);
        return req;
    }
}
