package com.mnc.ausy.service;

import org.junit.jupiter.api.Test;

import java.util.concurrent.*;
import java.util.concurrent.locks.ReentrantLock;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Dua penyusunan berkas ACCTXT tidak boleh berjalan bersamaan.
 *
 * Penjaga "sudah digenerate" hanya kolom file_name, yang dibaca di awal dan ditulis di akhir.
 * Dua penyusunan serentak — penjadwal jam 23:00 dan seseorang menekan tombol pada detik yang
 * sama — sama-sama membaca "belum digenerate" lalu saling menimpa berkasnya.
 *
 * Yang diuji di sini perilaku gemboknya: yang kedua DITOLAK, bukan diantrikan. Jalur
 * sesungguhnya diperiksa lewat aplikasi berjalan karena menyentuh basis data dan berkas.
 */
class GenerateSerentakTest {

    /** Tiruan setia dari pola yang dipakai AcctxtGenerateService. */
    private static final class Penyusun {
        private final ReentrantLock gembok = new ReentrantLock();
        private final CountDownLatch masuk = new CountDownLatch(1);
        private final CountDownLatch tahan = new CountDownLatch(1);

        String jalankan() {
            if (!gembok.tryLock()) return "DITOLAK";
            try {
                masuk.countDown();
                tahan.await(5, TimeUnit.SECONDS);
                return "JALAN";
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return "TERPUTUS";
            } finally {
                gembok.unlock();
            }
        }
    }

    @Test
    void penyusunanKeduaDitolakSelagiYangPertamaBerjalan() throws Exception {
        Penyusun p = new Penyusun();
        ExecutorService ex = Executors.newFixedThreadPool(2);
        try {
            Future<String> pertama = ex.submit(p::jalankan);
            assertTrue(p.masuk.await(2, TimeUnit.SECONDS), "yang pertama harus sudah masuk");

            // Yang kedua datang selagi yang pertama belum selesai.
            assertEquals("DITOLAK", ex.submit(p::jalankan).get(2, TimeUnit.SECONDS));

            p.tahan.countDown();
            assertEquals("JALAN", pertama.get(5, TimeUnit.SECONDS));
        } finally {
            ex.shutdownNow();
        }
    }

    @Test
    void sesudahSelesaiGembokDilepas() throws Exception {
        Penyusun p = new Penyusun();
        p.tahan.countDown();                       // selesai seketika
        assertEquals("JALAN", p.jalankan());
        // Giliran berikutnya harus bisa masuk — gembok tidak boleh tertinggal terkunci.
        assertEquals("JALAN", p.jalankan());
    }
}
