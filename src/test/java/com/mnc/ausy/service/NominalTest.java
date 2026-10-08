package com.mnc.ausy.service;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;

/** REQ_AUSY_00029 — format Indonesia ditulis dan dibaca kembali tanpa berubah nilai. */
class NominalTest {

    private static void sama(String expected, BigDecimal actual) {
        assertNotNull(actual);
        assertEquals(0, new BigDecimal(expected).compareTo(actual), "dibaca " + actual);
    }

    @Test
    void formatIndonesia() {
        assertEquals("1.000.000,50", Nominal.format(new BigDecimal("1000000.5")));
        assertEquals("0,00", Nominal.format(null));
    }

    @Test
    void hasilFormatTerbacaKembali() {
        for (String v : new String[]{"0", "7", "999.99", "1000000.5", "1642798", "123456789012.34"})
            sama(v, Nominal.parse(Nominal.format(new BigDecimal(v))));
    }

    @Test
    void bentukLainYangWajar() {
        sama("1000000.50", Nominal.parse("1.000.000,50"));     // Indonesia
        sama("1000000.50", Nominal.parse("1,000,000.50"));     // Inggris
        sama("1000000.5", Nominal.parse("1000000.5"));         // sel Excel numerik
        sama("1000000", Nominal.parse("1000000"));
        sama("1000000.50", Nominal.parse("1000000,50"));       // koma desimal saja
        sama("1000000", Nominal.parse("1.000.000"));           // titik ribuan saja
        sama("1000000", Nominal.parse("1,000,000"));           // koma ribuan saja
        sama("1000000.50", Nominal.parse(" 1.000.000,50 "));   // spasi di ujung
    }

    @Test
    void yangTidakTerbacaNull() {
        assertNull(Nominal.parse("abc"));
        assertNull(Nominal.parse(""));
        assertNull(Nominal.parse(null));
        assertNull(Nominal.parse("1.0.0,5,5"));
    }
}
