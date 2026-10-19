package com.mnc.ausy.service;

import com.mnc.ausy.domain.User;
import com.mnc.ausy.repo.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** REQ_AUSY_00064 — user mengganti password sendiri. */
class AccountPasswordServiceTest {

    private final BCryptPasswordEncoder enc = new BCryptPasswordEncoder();
    private UserRepository users;
    private AuditService audit;
    private AccountPasswordService service;
    private User user;

    @BeforeEach
    void setUp() {
        users = mock(UserRepository.class);
        audit = mock(AuditService.class);
        user = new User();
        user.setUserId("budi");
        user.gantiPassword(enc.encode("LamaPass1"));
        user.setPassChangedAt(LocalDateTime.now().minusDays(29));
        when(users.findByUserId("budi")).thenReturn(Optional.of(user));
        service = new AccountPasswordService(users, enc, audit);
    }

    @Test
    void berhasilMenggantiDanMenghitungUlangMasaBerlaku() {
        var h = service.ganti("budi", "LamaPass1", "BaruPass9", "BaruPass9");
        assertTrue(h.ok(), h.pesan());
        assertTrue(enc.matches("BaruPass9", user.getPass()));
        assertTrue(user.getPassChangedAt().isAfter(LocalDateTime.now().minusMinutes(1)));   // masa 30 hari mulai lagi
        verify(users).save(user);
        verify(audit).transaction(eq("budi"), eq("PASSWORD_CHANGE"), any());
    }

    @Test
    void passwordLamaSalahDitolakDanTidakMengubahApaPun() {
        String hashAwal = user.getPass();
        var h = service.ganti("budi", "SALAH1234", "BaruPass9", "BaruPass9");
        assertFalse(h.ok());
        assertEquals("Password lama salah.", h.pesan());
        assertEquals(hashAwal, user.getPass());
        verify(users, never()).save(any());
        verify(audit).transaction(eq("budi"), eq("PASSWORD_CHANGE_GAGAL"), any());
    }

    @Test
    void konfirmasiHarusSama() {
        var h = service.ganti("budi", "LamaPass1", "BaruPass9", "BaruPass8");
        assertFalse(h.ok());
        assertTrue(h.pesan().contains("Konfirmasi"));
        verify(users, never()).save(any());
    }

    @Test
    void passwordBaruTidakBolehSamaDenganLama() {
        var h = service.ganti("budi", "LamaPass1", "LamaPass1", "LamaPass1");
        assertFalse(h.ok());
        assertTrue(h.pesan().contains("sama dengan password lama"));
    }

    @Test
    void kriteriaKeamanan() {
        assertNotNull(AccountPasswordService.cekKekuatan("Ab1", "budi"));              // terlalu pendek
        assertNotNull(AccountPasswordService.cekKekuatan("abcdefgh", "budi"));         // tanpa angka
        assertNotNull(AccountPasswordService.cekKekuatan("12345678", "budi"));         // tanpa huruf
        assertNotNull(AccountPasswordService.cekKekuatan("Budi12345", "budi"));        // memuat username
        assertNull(AccountPasswordService.cekKekuatan("Kopi4Susu", "budi"));
        assertNull(AccountPasswordService.cekKekuatan("Kopi4Susu!", "budi"));          // simbol boleh
    }

    @Test
    void passwordLemahDitolakSebelumDisimpan() {
        var h = service.ganti("budi", "LamaPass1", "abcdefgh", "abcdefgh");
        assertFalse(h.ok());
        verify(users, never()).save(any());
    }

    @Test
    void isiPasswordTidakPernahMasukAuditTrail() {
        service.ganti("budi", "LamaPass1", "BaruPass9", "BaruPass9");
        service.ganti("budi", "SALAH1234", "BaruPass9", "BaruPass9");
        verify(audit, atLeastOnce()).transaction(any(), any(), argThat(s -> !s.contains("LamaPass1")
                && !s.contains("BaruPass9") && !s.contains("SALAH1234")));
    }

    @Test
    void akunTidakDikenal() {
        assertFalse(service.ganti("hantu", "x", "BaruPass9", "BaruPass9").ok());
    }
}
