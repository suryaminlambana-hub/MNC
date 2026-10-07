package com.mnc.ausy.service;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;

/** REQ_AUSY_00012 — nominal di teks audit memakai titik ribuan dan koma desimal. */
class NominalAuditTest {

    @Test
    void ribuanTitikDesimalKoma() {
        assertEquals("5.000.000,00", UploadService.nominal(new BigDecimal("5000000")));
        assertEquals("1.642.798,50", UploadService.nominal(new BigDecimal("1642798.5")));
    }

    @Test
    void angkaKecilDanKosong() {
        assertEquals("0,00", UploadService.nominal(null));
        assertEquals("999,99", UploadService.nominal(new BigDecimal("999.99")));
    }
}
