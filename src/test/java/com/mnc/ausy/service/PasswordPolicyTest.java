package com.mnc.ausy.service;

import com.mnc.ausy.domain.User;
import com.mnc.ausy.repo.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Masa berlaku password dan pengingat H-3 — REQ_AUSY_00009.
 *
 * Angka 30 dan 3 diperiksa dari ujung ke ujung: salah satu hari saja membuat pengingat muncul
 * di waktu yang salah, dan keluhan aslinya ("password habis tiba-tiba") tidak terselesaikan.
 */
class PasswordPolicyTest {

    private UserRepository users;
    private PasswordPolicyService policy;
    private final List<User> isiTabel = new ArrayList<>();

    @BeforeEach
    void setUp() {
        users = mock(UserRepository.class);
        isiTabel.clear();
        when(users.findAll()).thenReturn(isiTabel);
        policy = new PasswordPolicyService(users);
    }

    /** User aktif yang passwordnya diganti {@code umurHari} hari yang lalu. */
    private User user(String id, int umurHari) {
        User u = new User();
        u.setUserId(id);
        u.setNama("Nama " + id);
        u.setStatusAccount((short) 1);
        u.setPassChangedAt(LocalDateTime.now().minusDays(umurHari));
        isiTabel.add(u);
        return u;
    }

    // ---- hitungan dasar ----

    @Test
    void passwordBaruBerlakuTigaPuluhHari() {
        User u = user("baru", 0);
        assertEquals(30, policy.sisaHari(u));
        assertEquals(LocalDate.now().plusDays(30), policy.kedaluwarsa(u));
        assertFalse(policy.perluDiingatkan(u));
    }

    @Test
    void hariKeDuaPuluhEnamBelumDiingatkan() {
        assertFalse(policy.perluDiingatkan(user("h26", 26)), "sisa 4 hari, belum masuk H-3");
    }

    /** Inti REQ-nya: hari ke-27 = sisa 3 hari = mulai diingatkan. */
    @Test
    void hariKeDuaPuluhTujuhMulaiDiingatkan() {
        User u = user("h27", 27);
        assertEquals(3, policy.sisaHari(u));
        assertTrue(policy.perluDiingatkan(u));
    }

    @Test
    void hariKeTigaPuluhHabisHariIni() {
        User u = user("h30", 30);
        assertEquals(0, policy.sisaHari(u));
        assertTrue(policy.perluDiingatkan(u));
        assertEquals("Habis hari ini", policy.label(u));
        assertEquals("danger", policy.warna(u));
    }

    @Test
    void lewatBatasDihitungNegatifDanTetapDiingatkan() {
        User u = user("lewat", 35);
        assertEquals(-5, policy.sisaHari(u));
        assertTrue(policy.perluDiingatkan(u));
        assertEquals("Lewat 5 hari", policy.label(u));
    }

    // ---- daftar pengingat ----

    @Test
    void daftarnyaHanyaBerisiYangMendekatiDanDiurutPalingMendesak() {
        user("aman", 10);
        user("h27", 27);
        user("besok", 29);
        user("sudahLewat", 33);

        List<PasswordPolicyService.Peringatan> p = policy.daftarPeringatan();

        assertEquals(List.of("sudahLewat", "besok", "h27"), p.stream().map(x -> x.userId()).toList());
        assertEquals(-3, p.get(0).sisaHari());
        assertTrue(p.get(0).lewat());
    }

    /** Akun non-aktif tidak bisa dipakai login, jadi memperingatkannya hanya menambah kebisingan. */
    @Test
    void akunNonAktifTidakIkutDiingatkan() {
        User u = user("nonaktif", 29);
        u.setStatusAccount((short) 0);

        assertFalse(policy.perluDiingatkan(u));
        assertTrue(policy.daftarPeringatan().isEmpty());
    }

    @Test
    void barisPengingatMemuatTanggalResetDanTanggalKedaluwarsa() {
        user("h28", 28);

        PasswordPolicyService.Peringatan p = policy.daftarPeringatan().get(0);

        assertEquals("h28", p.userId());
        assertEquals("Nama h28", p.nama());
        assertEquals(2, p.sisaHari());
        assertFalse(p.gantiTerakhir().isBlank(), "tanggal reset terakhir wajib ikut disebut");
        assertFalse(p.kedaluwarsa().isBlank(), "tanggal kedaluwarsa wajib ikut disebut");
        assertNotEquals(p.gantiTerakhir(), p.kedaluwarsa());
    }

    // ---- tanggal yang belum tercatat ----

    /**
     * Akun tanpa tanggal ganti tidak boleh ditampilkan seolah masih panjang — itu justru
     * menyembunyikan akun yang keadaannya tidak diketahui.
     */
    @Test
    void tanggalBelumTercatatTidakDianggapAman() {
        User u = new User();
        u.setUserId("lama");
        u.setStatusAccount((short) 1);
        isiTabel.add(u);

        assertNull(policy.sisaHari(u));
        assertNull(policy.kedaluwarsa(u));
        assertEquals("Belum tercatat", policy.label(u));
        assertTrue(policy.tanggalnyaBelumTercatat(u));
        assertFalse(policy.perluDiingatkan(u), "tidak bisa dihitung, jadi bukan peringatan palsu");
    }

    // ---- pencatatan tanggal saat password diganti ----

    /** Setiap penggantian password menyetel ulang hitungan 30 harinya. */
    @Test
    void gantiPasswordMenyetelUlangHitungan() {
        User u = user("reset", 29);
        assertEquals(1, policy.sisaHari(u));

        u.gantiPassword("$2a$10$hash-baru");

        assertEquals(30, policy.sisaHari(u));
        assertEquals("$2a$10$hash-baru", u.getPass());
        assertFalse(policy.perluDiingatkan(u));
    }
}
