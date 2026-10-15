package com.mnc.ausy.service;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pemisahan kolom CSV pada import Kode GL.
 *
 * Daftar Kode GL MNC memuat deskripsi yang di dalamnya ada koma. Dipisah mentah dengan
 * {@code split(",")}, deskripsinya terpotong diam-diam di koma itu dan yang tersimpan tinggal
 * separuh nama — tanpa ada yang menandai bahwa datanya hilang.
 */
class GlCodeCsvPisahTest {

    @Test
    void barisBiasaTerbacaSepertiDulu() {
        assertEquals(List.of("900", "KANTOR PUSAT OPERASIONAL"),
                GlCodeService.pisahCsv("900,KANTOR PUSAT OPERASIONAL"));
    }

    @Test
    void komaDiDalamTandaKutipBagianDariDeskripsi() {
        // Ketiganya ada sungguhan di daftar Kode GL MNC.
        assertEquals(List.of("10202017", "GPBL - UNION BANK OF CALIFORNIA, N.A"),
                GlCodeService.pisahCsv("10202017,\"GPBL - UNION BANK OF CALIFORNIA, N.A\""));
        assertEquals(List.of("50700006", "BBN KEND-TOL, PARKIR"),
                GlCodeService.pisahCsv("50700006,\"BBN KEND-TOL, PARKIR\""));
        assertEquals(List.of("51303002", "BBN KOM-SWIFT, TELEX & FAX"),
                GlCodeService.pisahCsv("51303002,\"BBN KOM-SWIFT, TELEX & FAX\""));
    }

    @Test
    void duaTandaKutipBerturutanBerartiSatuTandaKutip() {
        assertEquals(List.of("123", "KAS \"BESAR\" PUSAT"),
                GlCodeService.pisahCsv("123,\"KAS \"\"BESAR\"\" PUSAT\""));
    }

    @Test
    void tandaKutipDiTengahKolomDianggapIsiBiasa() {
        // Bukan pembuka bungkus: yang di tengah kolom tetap karakter biasa, bukan sintaks.
        assertEquals(List.of("123", "BANK 5\" PUSAT"),
                GlCodeService.pisahCsv("123,BANK 5\" PUSAT"));
    }

    @Test
    void kolomKosongTetapDihitung() {
        assertEquals(List.of("123", "", "KAS"), GlCodeService.pisahCsv("123,,KAS"));
        assertEquals(List.of("123", ""), GlCodeService.pisahCsv("123,"));
    }

    @Test
    void formatLamaTigaKolomMasihTerbaca() {
        assertEquals(List.of("900", "D", "KANTOR PUSAT"),
                GlCodeService.pisahCsv("900,D,KANTOR PUSAT"));
    }
}
