package com.mnc.ausy.security;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.*;

/** Pembatasan percobaan login — REQ_AUSY_00070. */
class LoginThrottleTest {

    /** Jam yang bisa dimajukan. */
    static class Jam extends Clock {
        Instant t = Instant.parse("2026-10-30T03:00:00Z");
        void maju(Duration d) { t = t.plus(d); }
        @Override public Instant instant() { return t; }
        @Override public java.time.ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(java.time.ZoneId z) { return this; }
    }

    @Test
    void usernameDikunciSetelahLimaKaliGagal() {
        Jam jam = new Jam(); LoginThrottle t = new LoginThrottle(jam);
        for (int i = 0; i < 4; i++) t.gagal("budi", "10.0.0.1");
        assertEquals(Duration.ZERO, t.sisaKunci("budi", "10.0.0.1"), "4 kali belum dikunci");
        t.gagal("budi", "10.0.0.1");
        assertEquals(LoginThrottle.KUNCI, t.sisaKunci("budi", "10.0.0.2"), "dikunci juga dari IP lain");
        assertEquals(LoginThrottle.KUNCI, t.sisaKunci("BUDI ", "10.0.0.3"), "besar-kecil huruf & spasi tidak mengakali");
        assertEquals(Duration.ZERO, t.sisaKunci("ani", "10.0.0.3"), "user lain tidak terdampak");
    }

    @Test
    void kunciLepasSetelahLimaBelasMenit() {
        Jam jam = new Jam(); LoginThrottle t = new LoginThrottle(jam);
        for (int i = 0; i < 5; i++) t.gagal("budi", "10.0.0.1");
        jam.maju(Duration.ofMinutes(14));
        assertTrue(t.sisaKunci("budi", "x").toMinutes() >= 0 && !t.sisaKunci("budi", "x").isZero());
        jam.maju(Duration.ofMinutes(1));
        assertEquals(Duration.ZERO, t.sisaKunci("budi", "x"));
    }

    @Test
    void gagalLamaDiLuarJendelaTidakDihitung() {
        Jam jam = new Jam(); LoginThrottle t = new LoginThrottle(jam);
        for (int i = 0; i < 4; i++) t.gagal("budi", "10.0.0.1");
        jam.maju(Duration.ofMinutes(16));
        t.gagal("budi", "10.0.0.1");
        assertEquals(Duration.ZERO, t.sisaKunci("budi", "10.0.0.1"));
    }

    @Test
    void loginBerhasilMenghapusHitungan() {
        Jam jam = new Jam(); LoginThrottle t = new LoginThrottle(jam);
        for (int i = 0; i < 4; i++) t.gagal("budi", "10.0.0.1");
        t.berhasil("budi");
        for (int i = 0; i < 4; i++) t.gagal("budi", "10.0.0.9");
        assertEquals(Duration.ZERO, t.sisaKunci("budi", "10.0.0.9"));
    }

    @Test
    void ipDikunciSaatMencobaBanyakUsername() {
        Jam jam = new Jam(); LoginThrottle t = new LoginThrottle(jam);
        for (int i = 0; i < LoginThrottle.MAKS_GAGAL_IP; i++) t.gagal("user" + i, "10.9.9.9");
        assertFalse(t.sisaKunci("siapa", "10.9.9.9").isZero(), "IP penyerang dikunci");
        assertTrue(t.sisaKunci("siapa", "10.0.0.1").isZero(), "IP lain tidak");
    }
}
