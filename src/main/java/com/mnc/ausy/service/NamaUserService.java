package com.mnc.ausy.service;

import com.mnc.ausy.domain.User;
import com.mnc.ausy.repo.UserRepository;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * REQ_AUSY_00055 — pencatatan yang menyebut user menampilkan NAMA, bukan username (audit trail,
 * riwayat, uploader, maker/checker, diajukan/di-approve/di-return oleh, dsb.).
 * <p>
 * Yang disimpan di database tetap username (kunci yang dipakai pembatasan data dan audit); nama
 * hanya dipakai saat menampilkan. User yang tidak ditemukan atau belum punya nama tampil dengan
 * username-nya, jadi tidak ada baris yang kosong.
 * <p>
 * Di template: {@code ${@nama.of(x)}}.
 */
@Component("nama")
public class NamaUserService {
    /** Daftar user kecil dan jarang berubah: dimuat ulang paling lama tiap 30 detik. */
    private static final long SEGAR_MS = 30_000;

    private final UserRepository users;
    private volatile Map<String, String> peta = Map.of();
    private volatile long dimuat;

    public NamaUserService(UserRepository users) {
        this.users = users;
    }

    /** Nama user, atau username-nya sendiri bila nama tidak ada. */
    public String of(Object username) {
        if (username == null) return "";
        String u = username.toString().trim();
        if (u.isEmpty()) return "";
        String n = peta().get(u);
        return n == null || n.isBlank() ? u : n;
    }

    private Map<String, String> peta() {
        long now = System.currentTimeMillis();
        if (now - dimuat > SEGAR_MS) {
            Map<String, String> m = new HashMap<>();
            for (User x : users.findAll())
                if (x.getUserId() != null && x.getNama() != null) m.put(x.getUserId().trim(), x.getNama().trim());
            peta = m;
            dimuat = now;
        }
        return peta;
    }
}
