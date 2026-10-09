package com.mnc.ausy.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** REQ_AUSY_00031 — nomor batch tampil mulai 1 setiap hari; nomor di database tetap unik. */
class BatchLabelTest {

    @Test
    void nomorHarian() {
        assertEquals("1 (09-10-2026)", BatchLabel.label("20261009001"));
        assertEquals("12 (09-10-2026)", BatchLabel.label("20261009012"));
        assertEquals("1 (10-10-2026)", BatchLabel.label("20261010001"));
    }

    @Test
    void formatLamaTampilApaAdanya() {
        assertEquals("03", BatchLabel.label("03"));
        assertEquals("(manual)", BatchLabel.label("(manual)"));
        assertEquals("", BatchLabel.label(null));
        assertEquals("", new BatchLabel().of(null));
    }
}
