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
    /**
     * Otorisasi yang sedang diproses berasal dari hari sebelumnya? (REQ_AUSY_00060)
     *
     * Aturan bisnisnya: otorisasi yang melewati batas hari dan baru diproses sekarang TIDAK
     * dikembalikan ke maker — datanya langsung hilang. Yang diproses pada hari yang sama tetap
     * mengikuti alur normal.
     *
     * Acuannya {@code adddt}, yaitu kapan barisnya diunggah, bukan tanggal posting: tanggal
     * posting bisa diatur terpisah, sedangkan yang dimaksud "otorisasi kemarin" adalah
     * pengajuannya yang menginap.
     */
    public static boolean lintasHari(java.time.LocalDateTime diajukan) {
        return diajukan != null && diajukan.toLocalDate().isBefore(java.time.LocalDate.now());
    }

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
