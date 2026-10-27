package com.mnc.ausy.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Format nomor referensi GLTRRF — REQ_AUSY_00066, 00067 &amp; 00068. */
class ReferensiTest {

    @Test
    void formatBaruTahunDuaDigitDanRunningNumberEnamDigit() {
        assertEquals("261027001-01-000001", Referensi.of("20261027001", 1));
    }

    @Test
    void runningNumberDiberikanPemanggil() {
        // Angkanya (reset per hari, berlanjut antar-batch) dihitung dari basis data; di sini
        // hanya bentuknya: batch kedua hari itu melanjutkan nomor batch pertama.
        assertEquals("261027002-01-020001", Referensi.of("20261027002", 20001));
        assertEquals("261028001-01-000001", Referensi.of("20261028001", 1), "hari berikutnya mulai dari 000001");
    }

    @Test
    void tanggalDiambilDariNomorBatch() {
        assertEquals("20261027", Referensi.tanggal("20261027001"));
        assertEquals("20261027", Referensi.tanggal("202610271000"));
        assertNull(Referensi.tanggal("REF"), "batch lama tanpa tanggal");
        assertNull(Referensi.tanggal(null));
    }

    @Test
    void selaluMuatDiKolomDuaPuluhKarakter() {
        // batch ke-1000 di hari yang sama = nomor batch 4 digit; running number paling besar
        assertEquals(19, Referensi.of("20261027001", Referensi.MAKS_URUT).length());
        assertEquals(20, Referensi.of("202610271000", Referensi.MAKS_URUT).length());
    }

    @Test
    void nomorBatchBukanFormatTanggalTidakDipotong() {
        assertEquals("REF-01-000003", Referensi.of("REF", 3));
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
