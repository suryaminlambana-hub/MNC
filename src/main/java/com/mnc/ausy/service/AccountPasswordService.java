package com.mnc.ausy.service;

import com.mnc.ausy.domain.User;
import com.mnc.ausy.repo.UserRepository;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * User mengganti password SENDIRI lewat pop-up Ganti Password (REQ_AUSY_00064).
 *
 * Berbeda dengan reset oleh Admin ({@code ConfigChangeService}) yang lewat otorisasi Admin Approval:
 * ini pemilik akunnya sendiri, dibuktikan dengan password lama, jadi tidak ada pihak kedua.
 * Tanggal ganti dicatat lewat {@link User#gantiPassword}, sehingga masa berlaku 30 hari
 * (REQ_AUSY_00009) mulai dihitung ulang.
 */
@Service
public class AccountPasswordService {

    public static final int MIN_PANJANG = 8;

    public record Hasil(boolean ok, String pesan) {}

    private final UserRepository users;
    private final PasswordEncoder encoder;
    private final AuditService audit;

    public AccountPasswordService(UserRepository users, PasswordEncoder encoder, AuditService audit) {
        this.users = users; this.encoder = encoder; this.audit = audit;
    }

    /** Syarat password baru yang tidak terpenuhi, atau null bila semuanya terpenuhi. */
    public static String cekKekuatan(String baru, String username) {
        if (baru == null || baru.length() < MIN_PANJANG)
            return "Password baru minimal " + MIN_PANJANG + " karakter.";
        boolean huruf = baru.chars().anyMatch(Character::isLetter);
        boolean angka = baru.chars().anyMatch(Character::isDigit);
        if (!huruf || !angka)
            return "Password baru harus kombinasi huruf dan angka (simbol dianjurkan).";
        if (username != null && baru.toLowerCase().contains(username.toLowerCase()))
            return "Password baru tidak boleh memuat username Anda.";
        return null;
    }

    /**
     * @return {@code ok} bila berhasil. Pesan gagalnya sengaja tidak membedakan lebih dari yang
     *         perlu: password lama salah disebut apa adanya (pemiliknya sudah login), tapi isi
     *         password tidak pernah ikut ke pesan maupun audit trail.
     */
    @Transactional
    public Hasil ganti(String username, String lama, String baru, String konfirmasi) {
        if (lama == null || lama.isEmpty()) return new Hasil(false, "Password lama wajib diisi.");
        if (baru == null || baru.isEmpty()) return new Hasil(false, "Password baru wajib diisi.");
        if (konfirmasi == null || !baru.equals(konfirmasi))
            return new Hasil(false, "Konfirmasi password tidak sama dengan password baru.");
        User u = username == null ? null : users.findByUserId(username).orElse(null);
        if (u == null) return new Hasil(false, "Akun tidak ditemukan.");
        if (!encoder.matches(lama, u.getPass())) {
            audit.transaction(username, "PASSWORD_CHANGE_GAGAL", "Ganti password sendiri gagal: password lama salah.");
            return new Hasil(false, "Password lama salah.");
        }
        if (baru.equals(lama)) return new Hasil(false, "Password baru tidak boleh sama dengan password lama.");
        String lemah = cekKekuatan(baru, username);
        if (lemah != null) return new Hasil(false, lemah);

        u.gantiPassword(encoder.encode(baru));
        users.save(u);
        audit.transaction(username, "PASSWORD_CHANGE", "Mengganti password sendiri.");
        return new Hasil(true, "Password berhasil diubah. Silakan login kembali dengan password baru.");
    }
}
