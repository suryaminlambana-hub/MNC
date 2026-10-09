package com.mnc.ausy.service;

import com.mnc.ausy.domain.User;
import com.mnc.ausy.repo.UserRepository;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** REQ_AUSY_00055 — user disebut dengan nama, termasuk di teks keterangan audit trail. */
class NamaUserServiceTest {

    private static User user(String id, String nama) {
        User u = new User();
        u.setUserId(id);
        u.setNama(nama);
        return u;
    }

    private final NamaUserService nama;

    NamaUserServiceTest() {
        UserRepository repo = mock(UserRepository.class);
        when(repo.findAll()).thenReturn(List.of(user("user", "Budi Santoso"), user("spv", "Admin Utama"),
                user("tanpa_nama", "")));
        nama = new NamaUserService(repo);
    }

    @Test
    void namaDariUsername() {
        assertEquals("Budi Santoso", nama.of("user"));
        assertEquals("tanpa_nama", nama.of("tanpa_nama"));      // nama kosong: tetap username
        assertEquals("tidak_ada", nama.of("tidak_ada"));        // user tidak dikenal
        assertEquals("", nama.of(null));
    }

    @Test
    void teksAuditTrail() {
        assertEquals("Batch 1 (maker=Budi Santoso) DITOLAK, 5 baris dikembalikan ke maker berstatus Rejected."
                        + " Catatan: ga balance",
                nama.teks("Batch 1 (maker=user) DITOLAK, 5 baris dikembalikan ke maker berstatus Rejected."
                        + " Catatan: ga balance"));
        assertEquals("Unduh PDF (uploader=Admin Utama): 2 baris", nama.teks("Unduh PDF (uploader=spv): 2 baris"));
        // "makerX=" bukan rujukan user; teks tanpa rujukan tidak berubah.
        assertEquals("Login berhasil", nama.teks("Login berhasil"));
        assertEquals("", nama.teks(null));
    }
}
