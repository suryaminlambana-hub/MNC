package com.mnc.ausy.service;

import com.mnc.ausy.domain.GlTabel;
import com.mnc.ausy.security.AusyUserDetails;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.function.Function;

/**
 * Data GL siapa yang boleh dilihat seseorang (BUG_AUSY_00138 &amp; BUG_AUSY_00139).
 *
 * Tiga perlakuan, ditentukan dari peran role-nya — bukan dari id role yang ditulis di kode:
 * <ul>
 *   <li><b>Maker</b> (mengerjakan data GL dan pengajuannya diotorisasi role lain) — hanya baris
 *       yang dia input sendiri. Dua maker tidak lagi saling melihat isi data.</li>
 *   <li><b>Checker</b> (penyetuju sebuah role maker) — baris milik maker yang dipasangkan padanya
 *       di menu Pasangan Checker - Maker, ditambah barisnya sendiri. Checker yang belum punya
 *       pasangan tidak melihat data siapa pun.</li>
 *   <li><b>Sisanya</b> (Admin, Admin Approval, halaman monitoring) — tanpa batas; halaman GL yang
 *       mereka pegang memang bersifat pengawasan.</li>
 * </ul>
 *
 * Batas ini dipakai bersama oleh List GL Aktif, Kelola Data GL, Hapus Batch, Rekapitulasi, card
 * dashboard, daftar pilihan Ajukan Export, dan antrian otorisasi — supaya tidak ada satu pun
 * jalan yang masih membocorkan data maker lain.
 */
@Service
public class DataScopeService {

    private final PairingService pairing;

    public DataScopeService(PairingService pairing) { this.pairing = pairing; }

    /** Pesan baku ketika checker menyentuh pengajuan maker yang bukan pasangannya. */
    public static final String NOT_MY_MAKER =
            "Maker tersebut bukan pasangan otorisasi Anda. Hubungi Administrator bila pemasangannya perlu diubah.";

    /**
     * Username maker yang datanya boleh dilihat, atau {@code null} bila tanpa batas.
     * Set kosong berarti benar-benar tidak ada — bukan "semua".
     */
    public Set<String> visibleMakers(AusyUserDetails me) {
        if (me == null) return Set.of();
        Integer g = me.getIdGroup();
        if (pairing.isMakerGroup(g)) return Set.of(me.getUsername());
        if (pairing.isCheckerGroup(g)) {
            Set<String> out = new LinkedHashSet<>(pairing.makersOf(me.getUsername()));
            out.add(me.getUsername());
            return out;
        }
        return null;                       // admin / monitoring: lihat semuanya
    }

    /** Apakah baris milik {@code maker} boleh dilihat user ini? */
    public boolean canSee(AusyUserDetails me, String maker) {
        Set<String> allowed = visibleMakers(me);
        return allowed == null || allowed.contains(maker);
    }

    /** Apakah pandangan user ini memang dibatasi? Dipakai untuk menampilkan keterangannya di layar. */
    public boolean restricted(AusyUserDetails me) { return visibleMakers(me) != null; }

    /** Saring baris GL apa adanya. */
    public List<GlTabel> filterRows(AusyUserDetails me, List<GlTabel> rows) {
        return filterBy(me, rows, GlTabel::getGltrus);
    }

    /** Saring koleksi apa pun dengan cara mengambil nama maker-nya dari tiap elemen. */
    public <T> List<T> filterBy(AusyUserDetails me, List<T> items, Function<T, String> makerOf) {
        if (items == null) return List.of();
        Set<String> allowed = visibleMakers(me);
        if (allowed == null) return items;
        List<T> out = new ArrayList<>();
        for (T it : items) if (allowed.contains(makerOf.apply(it))) out.add(it);
        return out;
    }

    /** Saring hasil query native yang mengembalikan {@code Object[]}, dengan kolom maker di index tertentu. */
    public List<Object[]> filterArrays(AusyUserDetails me, List<Object[]> rows, int makerIdx) {
        return filterBy(me, rows, r -> r.length > makerIdx && r[makerIdx] != null ? r[makerIdx].toString() : null);
    }
}
