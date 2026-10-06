package com.mnc.ausy.service;

import com.mnc.ausy.domain.User;
import com.mnc.ausy.repo.UserRepository;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Masa berlaku password dan pengingatnya (REQ_AUSY_00009).
 *
 * Kebijakannya: password berlaku {@value #MASA_AKTIF_HARI} hari sejak terakhir diganti, dan
 * {@value #AMBANG_PERINGATAN_HARI} hari sebelum habis akun itu mulai diingatkan — "H-3" pada
 * hari ke-27.
 *
 * <h2>Pengingat DAN penguncian</h2>
 * Hitungan yang sama dipakai dua kali: untuk memperingatkan sebelum habis (REQ_AUSY_00009) dan
 * untuk menolak login setelah habis (BUG_AUSY_00190). Perhitungannya dibuat statis supaya
 * {@code AusyUserDetails} memakai kode yang sama persis — kalau gerbang login punya salinan
 * aturannya sendiri, suatu saat keduanya akan berbeda dan layar akan menjanjikan sesuatu yang
 * tidak ditepati pintu masuknya.
 *
 * <h2>Akun Utama tidak pernah kedaluwarsa (REQ_AUSY_00010)</h2>
 * mncadmin dan mncsuperadmin dikecualikan. Bukan kemurahan hati: merekalah satu-satunya jalan
 * memperbaiki keadaan dari dalam aplikasi. Kalau keduanya ikut terkunci, tidak ada seorang pun
 * yang bisa mereset password siapa pun lagi — sistemnya mati kunci dan hanya bisa dibuka lewat
 * basis data langsung.
 */
@Service
public class PasswordPolicyService {

    /** Masa berlaku password sejak terakhir diganti. */
    public static final int MASA_AKTIF_HARI = 30;

    /** Sisa hari saat pengingat mulai muncul — H-3, yaitu hari ke-27. */
    public static final int AMBANG_PERINGATAN_HARI = 3;

    private static final DateTimeFormatter TGL =
            DateTimeFormatter.ofPattern("d MMM yyyy", new Locale("id", "ID"));

    private final UserRepository users;

    public PasswordPolicyService(UserRepository users) { this.users = users; }

    /** Satu baris pengingat, siap ditampilkan — sengaja memuat angka DAN tanggalnya. */
    public record Peringatan(String userId, String nama, String gantiTerakhir,
                             String kedaluwarsa, long sisaHari) {
        /** Sudah lewat batas, bukan sekadar mendekati. */
        public boolean lewat() { return sisaHari < 0; }
        /** Habis hari ini juga. */
        public boolean hariIni() { return sisaHari == 0; }
    }

    /**
     * Akun yang dikecualikan dari masa berlaku password (REQ_AUSY_00010) — Akun Utama bawaan
     * sistem, yaitu mncadmin dan mncsuperadmin.
     *
     * Ditandai dari {@code protected_account}, bukan dari user_id yang ditulis di kode: itu
     * penanda yang sama yang sudah dipakai untuk melarang keduanya dihapus (BUG_AUSY_00131),
     * dan alasannya pun sama — keduanya adalah jalan terakhir memperbaiki keadaan dari dalam
     * aplikasi.
     */
    public static boolean dikecualikan(User u) {
        return u != null && u.isProtectedAccount();
    }

    /** Tanggal password ini kedaluwarsa; null kalau dikecualikan atau tanggal gantinya belum tercatat. */
    public static LocalDate kedaluwarsa(User u) {
        if (u == null || dikecualikan(u) || u.getPassChangedAt() == null) return null;
        return u.getPassChangedAt().toLocalDate().plusDays(MASA_AKTIF_HARI);
    }

    /**
     * Sudah lewat masa berlakunya — dasar penolakan login (BUG_AUSY_00190).
     *
     * <p>Tanggal yang belum tercatat TIDAK dianggap kedaluwarsa. Mengunci akun karena keadaannya
     * tidak diketahui adalah menghukum orang atas data yang hilang; di layar ia tetap ditandai
     * "Belum tercatat" supaya admin melihatnya dan bisa mereset.
     */
    public static boolean sudahLewat(User u) {
        LocalDate exp = kedaluwarsa(u);
        return exp != null && LocalDate.now().isAfter(exp);
    }

    /**
     * Sisa hari sampai kedaluwarsa; negatif berarti sudah lewat, null berarti belum bisa
     * dihitung.
     *
     * <p>Null dikembalikan apa adanya, bukan diterjemahkan menjadi angka aman. Akun yang tanggal
     * gantinya hilang adalah akun yang tidak diketahui keadaannya, dan menampilkannya seolah
     * masih panjang justru menyembunyikan hal yang perlu dilihat orang.
     */
    public static Long sisaHari(User u) {
        LocalDate exp = kedaluwarsa(u);
        return exp == null ? null : ChronoUnit.DAYS.between(LocalDate.now(), exp);
    }

    /** Sudah masuk masa pengingat (termasuk yang sudah lewat batas). */
    public static boolean perluDiingatkan(User u) {
        if (u == null || u.getStatusAccount() == null || u.getStatusAccount() != 1) return false;
        Long sisa = sisaHari(u);
        return sisa != null && sisa <= AMBANG_PERINGATAN_HARI;
    }

    /** Akun aktif yang tanggal ganti passwordnya belum tercatat — tidak bisa dihitung. */
    public static boolean tanggalnyaBelumTercatat(User u) {
        return u != null && u.getStatusAccount() != null && u.getStatusAccount() == 1
                && !dikecualikan(u) && u.getPassChangedAt() == null;
    }

    /**
     * Seluruh akun aktif yang perlu diingatkan, yang paling mendesak di depan.
     *
     * Hanya akun aktif: akun non-aktif sudah tidak bisa dipakai login, jadi memperingatkan
     * passwordnya hanya menambah kebisingan pada peringatan yang justru harus ditindaklanjuti.
     */
    public List<Peringatan> daftarPeringatan() {
        List<Peringatan> out = new ArrayList<>();
        for (User u : users.findAll()) {
            if (!perluDiingatkan(u)) continue;
            Long sisa = sisaHari(u);
            out.add(new Peringatan(
                    u.getUserId(),
                    u.getNama() == null || u.getNama().isBlank() ? "—" : u.getNama(),
                    u.getPassChangedAt().toLocalDate().format(TGL),
                    kedaluwarsa(u).format(TGL),
                    sisa));
        }
        out.sort(Comparator.comparingLong(Peringatan::sisaHari));
        return out;
    }

    /** Label singkat untuk kolom di halaman User — "sisa 5 hari", "habis hari ini", dst. */
    public static String label(User u) {
        if (u == null) return "—";
        if (dikecualikan(u)) return "Tidak kedaluwarsa";
        if (u.getPassChangedAt() == null) return "Belum tercatat";
        long sisa = sisaHari(u);
        if (sisa < 0)  return "Lewat " + (-sisa) + " hari";
        if (sisa == 0) return "Habis hari ini";
        return "Sisa " + sisa + " hari";
    }

    /** Warna Bootstrap untuk label di atas, supaya yang mendesak terbaca lebih dulu. */
    public static String warna(User u) {
        if (u == null || dikecualikan(u) || u.getPassChangedAt() == null) return "secondary";
        long sisa = sisaHari(u);
        if (sisa <= 0) return "danger";
        if (sisa <= AMBANG_PERINGATAN_HARI) return "warning";
        return "success";
    }
}
