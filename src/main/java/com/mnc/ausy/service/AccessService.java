package com.mnc.ausy.service;

import com.mnc.ausy.domain.Menu;
import com.mnc.ausy.repo.MenuRepository;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * Hak akses sebagai data, bukan daftar group yang di-hardcode (BUG_AUSY_00112/00113/00114).
 *
 * Tiga jenis item (kolom {@code menu.kind}):
 * <ul>
 *   <li>{@code MENU}   — halaman: muncul di sidebar dan URL-nya boleh dibuka</li>
 *   <li>{@code ACTION} — endpoint tanpa menu sendiri, mis. "Ajukan Export" yang tidak boleh
 *       ikut terbuka hanya karena group punya halaman List GL Aktif</li>
 *   <li>{@code CARD}   — card/section dashboard (url diisi "card:" + key)</li>
 * </ul>
 *
 * <b>Sejak BUG_AUSY_00143 hak akses melekat pada USER, bukan role.</b> Dua sumber dipakai
 * berdampingan dan perannya berbeda:
 * <ul>
 *   <li>{@code user_menu}  — hak yang berlaku saat seseorang memakai aplikasi: sidebar, izin URL,
 *       card dashboard. Inilah yang diatur di halaman Access User.</li>
 *   <li>{@code group_menu} — cetakan milik role: nilai awal user baru, dan dasar pertanyaan yang
 *       memang setingkat role ("role ini penyetuju config?", "sisi kerja role ini apa?").</li>
 * </ul>
 *
 * Aturan URL: <b>pemilik terpanjang yang menang</b>. Untuk sebuah path dicari item dengan URL
 * terpanjang yang cocok (sama persis atau diikuti "/"), lalu ditanyakan apakah user memilikinya.
 * Jadi {@code /gl-legacy/export-request} dimiliki ACTION-nya sendiri, bukan menu {@code /gl-legacy},
 * dan {@code /gl-data/bulk-export} ikut menu {@code /gl-data}. Path yang tidak dimiliki item mana
 * pun (dashboard, ganti password, dsb.) terbuka untuk semua yang sudah login — sama seperti
 * aturan {@code anyRequest().authenticated()} sebelumnya.
 */
@Service
public class AccessService {

    private final MenuRepository menus;

    public AccessService(MenuRepository menus) { this.menus = menus; }

    // ---------------------------------------------------------------- per user (yang berlaku)

    /** Card dashboard yang boleh dilihat user ini (tanpa prefix "card:"). */
    public Set<String> cardsForUser(String userId) {
        Set<String> out = new LinkedHashSet<>();
        if (userId == null) return out;
        for (Menu m : menus.findGrantedItemsForUser(userId))
            if ("CARD".equals(m.getKind()) && !m.getCardKey().isEmpty()) out.add(m.getCardKey());
        return out;
    }

    /** Boleh atau tidak user ini membuka sebuah path aplikasi (context-path sudah dilepas). */
    public boolean allowedUser(String userId, String path) {
        Menu owner = ownerOf(path);
        if (owner == null) return true;                 // tidak dimiliki item mana pun
        if (userId == null) return false;
        for (Menu m : menus.findGrantedItemsForUser(userId))
            if (owner.getIdMenu().equals(m.getIdMenu())) return true;
        return false;
    }

    /** Apakah user ini punya halaman yang bisa mengajukan perubahan? */
    public boolean canSubmitUser(String userId) {
        if (userId == null) return false;
        for (Menu m : menus.findGrantedItemsForUser(userId))
            if (m.getUrl() != null && SUBMIT_PAGES.contains(m.getUrl().trim())) return true;
        return false;
    }

    // ---------------------------------------------------------------- per role (cetakan)

    /**
     * Halaman yang isinya pengajuan (maker/admin), bukan halaman baca atau halaman otorisasi.
     * Dipakai untuk memutuskan perlu tidaknya peringatan "belum punya approver" (BUG_AUSY_00116),
     * supaya group yang memang hanya menyetujui tidak ikut diperingati.
     */
    private static final Set<String> SUBMIT_PAGES = Set.of(
            "/users", "/groups", "/menus", "/access", "/pairing",
            "/upload", "/nala", "/gl-data", "/gl-delete", "/gl-legacy/export-request");

    /** Bagian dari {@link #SUBMIT_PAGES} yang benar-benar memasukkan/mengubah DATA GL. */
    private static final Set<String> GL_SUBMIT_PAGES = Set.of(
            "/upload", "/nala", "/gl-data", "/gl-delete", "/gl-legacy/export-request");

    /**
     * Apakah role ini mengerjakan data GL (bukan sekadar membacanya)? Dipakai membedakan checker
     * bisnis — yang pandangannya dibatasi pasangan maker — dari penyetuju sisi config yang
     * halaman GL-nya murni monitoring (BUG_AUSY_00138/00139).
     */
    public boolean canSubmitGl(Integer idGroup) {
        if (idGroup == null) return false;
        for (Menu m : menus.findGrantedItems(idGroup))
            if (m.getUrl() != null && GL_SUBMIT_PAGES.contains(m.getUrl().trim())) return true;
        return false;
    }

    /** Parent menu sisi administratif (Master Data, Otorisasi Administratif) — BUG_AUSY_00115. */
    private static final Set<Integer> CONFIG_PARENTS = Set.of(200, 450);
    /** Parent menu sisi operasional (Import, Data, Otorisasi GL, GL). */
    private static final Set<Integer> BUSINESS_PARENTS = Set.of(100, 300, 400, 500);

    /**
     * Sisi kerja sebuah group dilihat dari menu yang dipegangnya: {@code "CONFIG"} (admin &amp;
     * admin approval), {@code "BUSINESS"} (maker &amp; checker), atau null kalau memegang keduanya
     * / tidak memegang apa pun. Dipakai memisahkan isi Audit Trail tanpa mengunci id group
     * tertentu di kode (BUG_AUSY_00115).
     */
    public String sideOf(Integer idGroup) {
        if (idGroup == null) return null;
        boolean cfg = false, biz = false;
        for (Menu m : menus.findGrantedItems(idGroup)) {
            if (!"MENU".equals(m.getKind()) || m.getParent() == null) continue;
            if (CONFIG_PARENTS.contains(m.getParent())) cfg = true;
            if (BUSINESS_PARENTS.contains(m.getParent())) biz = true;
        }
        if (cfg == biz) return null;            // dua-duanya atau tidak sama sekali
        return cfg ? "CONFIG" : "BUSINESS";
    }

    /**
     * Section Access User yang relevan untuk sebuah role — dibaca dari item yang dipegang cetakan
     * role-nya. Halaman Access User memakainya supaya user ber-role Maker hanya memperlihatkan
     * centangan bagian Maker (BUG_AUSY_00143).
     *
     * <b>Kosong berarti "belum bisa disimpulkan", bukan "tidak boleh apa-apa"</b>: role buatan
     * admin memang sengaja lahir tanpa hak apa pun (BUG_AUSY_00112), jadi kalau hasilnya dipakai
     * mentah-mentah layar Access User-nya akan kosong dan role itu tidak akan pernah bisa diberi
     * hak. Pemanggilnya menafsirkan set kosong sebagai "tampilkan semua section" — lihat
     * {@link #showAllSections(Integer)}.
     */
    public Set<String> sectionsOf(Integer idGroup) {
        Set<String> out = new LinkedHashSet<>();
        if (idGroup == null) return out;
        for (Menu m : menus.findGrantedItems(idGroup)) {
            if (m.getParent() == null || m.getParent() == 0) continue;
            out.add(m.getSection() == null ? "" : m.getSection());
        }
        return out;
    }

    /** Role ini belum memegang apa pun, jadi seluruh section ditampilkan agar bisa mulai diisi. */
    public boolean showAllSections(Integer idGroup) { return sectionsOf(idGroup).isEmpty(); }

    /** Boleh atau tidak sebuah ROLE membuka path — pertanyaan setingkat role, bukan per user. */
    public boolean allowed(Integer idGroup, String path) {
        Menu owner = ownerOf(path);
        if (owner == null) return true;                 // tidak dimiliki item mana pun
        if (idGroup == null) return false;
        for (Menu m : menus.findGrantedItems(idGroup))
            if (owner.getIdMenu().equals(m.getIdMenu())) return true;
        return false;
    }

    /** Item akses yang "memiliki" path ini — URL terpanjang yang cocok. */
    private Menu ownerOf(String path) {
        String p = normalize(path);
        Menu best = null;
        for (Menu m : menus.findUrlOwners()) {
            String url = m.getUrl() == null ? "" : m.getUrl().trim();
            if (url.isEmpty() || !url.startsWith("/")) continue;
            if (!(p.equals(url) || p.startsWith(url + "/"))) continue;
            if (best == null || url.length() > best.getUrl().trim().length()) best = m;
        }
        return best;
    }

    private static String normalize(String path) {
        if (path == null || path.isBlank()) return "/";
        String p = path.trim();
        int q = p.indexOf('?');
        if (q >= 0) p = p.substring(0, q);
        if (p.length() > 1 && p.endsWith("/")) p = p.substring(0, p.length() - 1);
        return p;
    }
}
