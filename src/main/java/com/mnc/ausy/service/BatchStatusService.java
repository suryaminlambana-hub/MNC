package com.mnc.ausy.service;

import com.mnc.ausy.domain.GlBatchStatus;
import com.mnc.ausy.repo.GlBatchStatusRepository;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.*;

/**
 * REQ_AUSY_00053 — status batch upload untuk kolom Status di List Data GL:
 * Menunggu Otorisasi (tidak ada baris), Dikembalikan, atau Rejected, berikut catatan checker-nya.
 */
@Service
public class BatchStatusService {
    private final GlBatchStatusRepository repo;

    public BatchStatusService(GlBatchStatusRepository repo) {
        this.repo = repo;
    }

    /** Batch di-Return Data checker: kembali menunggu otorisasi, berstatus Dikembalikan. */
    public void returned(String paket, String maker, String checker, String note) {
        catat(paket, maker, GlBatchStatus.DIKEMBALIKAN, checker, note);
    }

    /** Batch ditolak checker: tetap tampil di List Data GL maker, berstatus Rejected. */
    public void rejected(String paket, String maker, String checker, String note) {
        catat(paket, maker, GlBatchStatus.REJECTED, checker, note);
    }

    private void catat(String paket, String maker, String status, String checker, String note) {
        GlBatchStatus s = repo.findByNoPaketAndMaker(paket, maker).orElseGet(GlBatchStatus::new);
        s.setNoPaket(paket); s.setMaker(maker); s.setStatus(status);
        s.setNote(note); s.setDecidedBy(checker); s.setDecidedAt(LocalDateTime.now());
        repo.save(s);
    }

    /** Batch di-approve, diajukan ulang, atau dihapus maker: statusnya tidak dicatat lagi. */
    public void clear(String paket, String maker) {
        repo.findByNoPaketAndMaker(paket, maker).ifPresent(repo::delete);
    }

    /** Status Dikembalikan/Rejected per batch, berkunci {@code no_paket|maker}. */
    public Map<String, GlBatchStatus> statusMap() {
        Map<String, GlBatchStatus> out = new HashMap<>();
        for (GlBatchStatus s : repo.findAll()) out.put(s.getNoPaket() + "|" + s.getMaker(), s);
        return out;
    }
}
