package com.mnc.ausy.service;

import com.mnc.ausy.domain.User;
import com.mnc.ausy.repo.UserRepository;
import com.mnc.ausy.security.AusyUserDetails;
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
        assertEquals(30, PasswordPolicyService.sisaHari(u));
        assertEquals(LocalDate.now().plusDays(30), PasswordPolicyService.kedaluwarsa(u));
        assertFalse(PasswordPolicyService.perluDiingatkan(u));
    }

    @Test
    void hariKeDuaPuluhEnamBelumDiingatkan() {
        assertFalse(PasswordPolicyService.perluDiingatkan(user("h26", 26)), "sisa 4 hari, belum masuk H-3");
    }

    /** Inti REQ-nya: hari ke-27 = sisa 3 hari = mulai diingatkan. */
    @Test
    void hariKeDuaPuluhTujuhMulaiDiingatkan() {
        User u = user("h27", 27);
        assertEquals(3, PasswordPolicyService.sisaHari(u));
        assertTrue(PasswordPolicyService.perluDiingatkan(u));
    }

    @Test
    void hariKeTigaPuluhHabisHariIni() {
        User u = user("h30", 30);
        assertEquals(0, PasswordPolicyService.sisaHari(u));
        assertTrue(PasswordPolicyService.perluDiingatkan(u));
        assertEquals("Habis hari ini", PasswordPolicyService.label(u));
        assertEquals("danger", PasswordPolicyService.warna(u));
    }

    @Test
    void lewatBatasDihitungNegatifDanTetapDiingatkan() {
        User u = user("lewat", 35);
        assertEquals(-5, PasswordPolicyService.sisaHari(u));
        assertTrue(PasswordPolicyService.perluDiingatkan(u));
        assertEquals("Lewat 5 hari", PasswordPolicyService.label(u));
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

        assertFalse(PasswordPolicyService.perluDiingatkan(u));
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

        assertNull(PasswordPolicyService.sisaHari(u));
        assertNull(PasswordPolicyService.kedaluwarsa(u));
        assertEquals("Belum tercatat", PasswordPolicyService.label(u));
        assertTrue(PasswordPolicyService.tanggalnyaBelumTercatat(u));
        assertFalse(PasswordPolicyService.perluDiingatkan(u), "tidak bisa dihitung, jadi bukan peringatan palsu");
    }

    // ---- BUG_AUSY_00190: gerbang login ----

    /**
     * Hari ke-30 adalah hari terakhir yang MASIH berlaku; penolakan baru mulai hari ke-31.
     * Label di layar dan gerbang login harus sepakat soal batas ini — kalau tidak, ada hari di
     * mana layar bilang "habis hari ini" sementara pintunya sudah menutup, atau sebaliknya.
     */
    @Test
    void batasPenolakanTepatSetelahHariKeTigaPuluh() {
        User h29 = user("h29", 29), h30 = user("h30", 30), h31 = user("h31", 31);

        assertFalse(PasswordPolicyService.sudahLewat(h29));
        assertFalse(PasswordPolicyService.sudahLewat(h30), "hari ke-30 masih boleh masuk");
        assertTrue(PasswordPolicyService.sudahLewat(h31), "hari ke-31 ditolak");

        assertEquals("Habis hari ini", PasswordPolicyService.label(h30));
        assertEquals("Lewat 1 hari", PasswordPolicyService.label(h31));
    }

    @Test
    void gerbangLoginMemakaiHitunganYangSama() {
        assertTrue(new AusyUserDetails(user("aman", 10)).isCredentialsNonExpired());
        assertFalse(new AusyUserDetails(user("lewat", 40)).isCredentialsNonExpired(),
                "password lewat masa berlaku harus menolak login");
    }

    /**
     * Spring Security harus menolaknya dengan CredentialsExpiredException, bukan sembarang
     * kegagalan — itulah tipe yang dipakai SecurityConfig untuk membedakan pesan "password
     * kedaluwarsa" dari "password salah". Kalau tipenya bergeser, pesannya diam-diam kembali
     * menjadi "username atau password salah" dan orangnya tidak pernah tahu harus minta reset.
     */
    @Test
    void springSecurityMenolaknyaSebagaiCredentialsExpired() {
        var checker = new org.springframework.security.authentication
                .AccountStatusUserDetailsChecker();

        assertDoesNotThrow(() -> checker.check(new AusyUserDetails(user("aman", 5))));

        assertThrows(org.springframework.security.authentication.CredentialsExpiredException.class,
                () -> checker.check(new AusyUserDetails(user("lewat", 45))));
    }

    /** Tanggal yang belum tercatat tidak boleh mengunci orang — itu menghukum atas data hilang. */
    @Test
    void tanggalBelumTercatatTidakMenguncinLogin() {
        User u = new User();
        u.setUserId("lama");
        u.setStatusAccount((short) 1);

        assertFalse(PasswordPolicyService.sudahLewat(u));
        assertTrue(new AusyUserDetails(u).isCredentialsNonExpired());
    }

    // ---- REQ_AUSY_00010: Akun Utama dikecualikan ----

    /**
     * mncadmin & mncsuperadmin tidak pernah kedaluwarsa. Kalau keduanya ikut terkunci, tidak ada
     * seorang pun yang bisa mereset password siapa pun lagi — sistemnya mati kunci.
     */
    @Test
    void akunUtamaTidakPernahKedaluwarsa() {
        User utama = user("mncsuperadmin", 400);     // jauh melewati 30 hari
        utama.setProtectedAccount((short) 1);

        assertTrue(PasswordPolicyService.dikecualikan(utama));
        assertNull(PasswordPolicyService.kedaluwarsa(utama));
        assertNull(PasswordPolicyService.sisaHari(utama));
        assertFalse(PasswordPolicyService.sudahLewat(utama));
        assertTrue(new AusyUserDetails(utama).isCredentialsNonExpired(), "tidak boleh terkunci");
        assertEquals("Tidak kedaluwarsa", PasswordPolicyService.label(utama));
    }

    @Test
    void akunUtamaTidakIkutMuncukDiPengingat() {
        User utama = user("mncadmin", 29);
        utama.setProtectedAccount((short) 1);
        user("biasa", 29);

        assertFalse(PasswordPolicyService.perluDiingatkan(utama));
        assertEquals(List.of("biasa"), policy.daftarPeringatan().stream().map(x -> x.userId()).toList());
    }

    // ---- pencatatan tanggal saat password diganti ----

    /** Setiap penggantian password menyetel ulang hitungan 30 harinya. */
    @Test
    void gantiPasswordMenyetelUlangHitungan() {
        User u = user("reset", 29);
        assertEquals(1, PasswordPolicyService.sisaHari(u));

        u.gantiPassword("$2a$10$hash-baru");

        assertEquals(30, PasswordPolicyService.sisaHari(u));
        assertEquals("$2a$10$hash-baru", u.getPass());
        assertFalse(PasswordPolicyService.perluDiingatkan(u));
    }
}
