package com.mnc.ausy.service;

import com.mnc.ausy.domain.GlBatchStatus;
import com.mnc.ausy.domain.GlTabel;
import com.mnc.ausy.domain.TUpload;
import com.mnc.ausy.repo.GlBatchStatusRepository;
import com.mnc.ausy.repo.TUploadRepository;
import com.mnc.ausy.security.AusyUserDetails;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;

/**
 * REQ_AUSY_00053 — status batch upload untuk kolom Status di List Data GL:
 * Menunggu Otorisasi (tidak ada baris), Dikembalikan, atau Rejected.
 */
@Service
public class BatchStatusService {
    private final GlBatchStatusRepository repo;
    private final AuditService audit;
    private final TUploadRepository uploads;

    public BatchStatusService(GlBatchStatusRepository repo, AuditService audit, TUploadRepository uploads) {
        this.repo = repo; this.audit = audit; this.uploads = uploads;
    }

    /** Batch di-Return Data checker: kembali menunggu otorisasi, berstatus Dikembalikan. */
    public void returned(String paket, String maker, String checker, String note) {
        GlBatchStatus s = repo.findByNoPaketAndMaker(paket, maker).orElseGet(GlBatchStatus::new);
        s.setNoPaket(paket); s.setMaker(maker);
        s.setStatus(GlBatchStatus.DIKEMBALIKAN);
        s.setNote(note); s.setDecidedBy(checker); s.setDecidedAt(LocalDateTime.now());
        repo.save(s);
    }

    /**
     * Batch ditolak checker. Baris GL-nya tetap dihapus pemanggil seperti dulu, jadi ringkasannya
     * disimpan di sini supaya maker masih melihat batch itu (berstatus Rejected) di List Data GL.
     */
    public void rejected(String paket, String maker, String checker, String note, List<GlTabel> rows) {
        GlBatchStatus s = repo.findByNoPaketAndMaker(paket, maker).orElseGet(GlBatchStatus::new);
        BigDecimal d = BigDecimal.ZERO, c = BigDecimal.ZERO;
        LocalDateTime uploaded = uploads.findByNoPaketIn(List.of(paket)).stream()
                .filter(t -> maker.equals(t.getUserId())).map(TUpload::getAdddt)
                .filter(Objects::nonNull).max(Comparator.naturalOrder()).orElse(null);
        for (GlTabel g : rows) {
            BigDecimal a = g.getGltram() == null ? BigDecimal.ZERO : g.getGltram();
            if ("D".equalsIgnoreCase(g.getGltrcd())) d = d.add(a); else c = c.add(a);
        }
        s.setNoPaket(paket); s.setMaker(maker);
        s.setStatus(GlBatchStatus.REJECTED);
        s.setNote(note); s.setDecidedBy(checker); s.setDecidedAt(LocalDateTime.now());
        s.setJml(rows.size()); s.setDebit(d); s.setCredit(c); s.setUploadedAt(uploaded);
        repo.save(s);
    }

    /** Batch di-approve atau dihapus maker: statusnya tidak dicatat lagi. */
    public void clear(String paket, String maker) {
        repo.findByNoPaketAndMaker(paket, maker).ifPresent(repo::delete);
    }

    /** Status Dikembalikan per batch yang menunggu, berkunci {@code no_paket|maker}. */
    public Map<String, GlBatchStatus> returnedMap() {
        Map<String, GlBatchStatus> out = new HashMap<>();
        for (GlBatchStatus s : repo.findByStatus(GlBatchStatus.DIKEMBALIKAN)) out.put(s.getNoPaket() + "|" + s.getMaker(), s);
        return out;
    }

    /** Batch Rejected milik maker ini, terbaru dulu. */
    public List<GlBatchStatus> rejectedOf(AusyUserDetails me) {
        if (me == null) return List.of();
        return repo.findByStatus(GlBatchStatus.REJECTED).stream()
                .filter(s -> me.getUsername().equals(s.getMaker()))
                .sorted(Comparator.comparing(GlBatchStatus::getDecidedAt, Comparator.nullsLast(Comparator.reverseOrder())))
                .toList();
    }

    /** Maker menghapus batch Rejected miliknya dari List Data GL. */
    @Transactional
    public String dismissRejected(String paket, AusyUserDetails me) {
        if (me == null) return "Sesi tidak valid.";
        GlBatchStatus s = repo.findByNoPaketAndMaker(paket, me.getUsername()).orElse(null);
        if (s == null || !GlBatchStatus.REJECTED.equals(s.getStatus()))
            return "Batch " + BatchLabel.label(paket) + " tidak ada di daftar batch Rejected Anda.";
        repo.delete(s);
        audit.transaction(me.getUsername(), "UPLOAD_REJECTED_DISMISS",
                "Batch " + BatchLabel.label(paket) + " (Rejected oleh " + s.getDecidedBy() + ") dihapus dari List Data GL.");
        return null;
    }
}
