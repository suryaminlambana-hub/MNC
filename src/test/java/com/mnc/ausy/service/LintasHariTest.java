package com.mnc.ausy.service;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;

/**
 * REQ_AUSY_00060 — otorisasi yang menginap diperlakukan berbeda.
 *
 * Yang diuji di sini penentuan "lintas hari"-nya saja; akibatnya (baris dihapus, tidak muncul
 * di List Data GL maupun Rekapitulasi) diperiksa lewat aplikasi yang berjalan, karena
 * menyangkut basis data.
 */
class LintasHariTest {

    @Test
    void diajukanKemarinDianggapLintasHari() {
        assertTrue(BatchStatusService.lintasHari(LocalDate.now().minusDays(1).atTime(23, 59)));
    }

    @Test
    void diajukanHariIniBukanLintasHari() {
        assertFalse(BatchStatusService.lintasHari(LocalDateTime.now()));
        // Tepat tengah malam hari ini masih hari ini — batas harinya jam 00:00, bukan 24 jam lalu.
        assertFalse(BatchStatusService.lintasHari(LocalDate.now().atStartOfDay()));
    }

    @Test
    void sedetikSebelumTengahMalamKemarinSudahLintasHari() {
        assertTrue(BatchStatusService.lintasHari(LocalDate.now().atStartOfDay().minusSeconds(1)));
    }

    @Test
    void tanpaTanggalTidakDianggapLintasHari() {
        // Baris lama yang adddt-nya kosong tidak boleh ikut terhapus hanya karena tidak tahu
        // kapan ia diunggah — jatuhnya ke perilaku normal, yang bisa dibatalkan maker.
        assertFalse(BatchStatusService.lintasHari(null));
    }
}
