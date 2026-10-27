package com.mnc.ausy.service;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

import static org.junit.jupiter.api.Assertions.*;

/** Format nomor referensi GLTRRF — REQ_AUSY_00066, 00067 &amp; 00068. */
class ReferensiTest {

    @Test
    void formatBaruTanggalEmpatDigitTanpaKodeBatchRunningNumberTujuhDigit() {
        // Contoh di tiket REQ_AUSY_00068: 27 Oktober 2026 -> 20261027-01-0000001
        assertEquals("20261027-01-0000001", Referensi.of("20261027001", 1));
    }

    @Test
    void kodeBatchTidakIkutDiReferensi() {
        // Batch kedua hari itu melanjutkan running number (REQ_AUSY_00066); nomor batchnya tidak
        // lagi terlihat di referensi.
        assertEquals("20261027-01-0020001", Referensi.of("20261027002", 20001));
        assertEquals("20261027-01-0020001", Referensi.of("202610271000", 20001));
        assertEquals("20261028-01-0000001", Referensi.of("20261028001", 1), "hari berikutnya mulai dari 0000001");
    }

    @Test
    void selaluSembilanBelasKarakter() {
        assertEquals(19, Referensi.of("20261027001", 1).length());
        assertEquals(19, Referensi.of("202610271000", Referensi.MAKS_URUT).length());
    }

    @Test
    void tanggalDiambilDariNomorBatch() {
        assertEquals("20261027", Referensi.tanggal("20261027001"));
        assertEquals("20261027", Referensi.tanggal("202610271000"));
        assertNull(Referensi.tanggal("REF"), "batch lama tanpa tanggal");
        assertNull(Referensi.tanggal(null));
    }

    @Test
    void batchLamaTanpaTanggalMemakaiTanggalHariIni() {
        String hariIni = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyyMMdd"));
        assertEquals(hariIni + "-01-0000003", Referensi.of("REF", 3));
    }

    @Test
    void pesanGagalTidakMembocorkanSql() {
        Exception e = new org.springframework.dao.DataIntegrityViolationException(
                "could not execute statement [ERROR: value too long for type character varying(20)]"
                + " [insert into gl_tabel (active,br_name) values (?,?)]");
        String pesan = UploadService.pesanGagal(e);
        assertFalse(pesan.contains("insert into"), pesan);
        assertFalse(pesan.contains("varying"), pesan);
        assertTrue(pesan.contains("Tidak ada data yang tersimpan"), pesan);
    }
}
