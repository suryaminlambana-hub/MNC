package com.mnc.ausy.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Nama berkas unggahan NALA tidak bisa keluar dari folder unggahan — REQ_AUSY_00071. */
class NalaNamaAmanTest {

    @Test
    void komponenFolderDibuang() {
        assertEquals("x.xlsx", NalaUploadService.namaAman("..\\..\\..\\Windows\\x.xlsx"));
        assertEquals("passwd", NalaUploadService.namaAman("../../etc/passwd"));
        assertEquals("x.xlsx", NalaUploadService.namaAman("C:\\data\\x.xlsx"));
    }

    @Test
    void namaBiasaTetapUtuh() {
        assertEquals("Jurnal GL 27-10.xlsx", NalaUploadService.namaAman("Jurnal GL 27-10.xlsx"));
    }

    @Test
    void karakterBerisikoDanTitikDiDepanDiganti() {
        assertEquals("env", NalaUploadService.namaAman(".env"));
        assertEquals("a_b_.xlsx", NalaUploadService.namaAman("a:b*.xlsx"));
        assertEquals("unggahan.xlsx", NalaUploadService.namaAman(".."));
        assertEquals("unggahan.xlsx", NalaUploadService.namaAman(null));
    }
}
