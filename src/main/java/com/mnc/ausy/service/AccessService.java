package com.mnc.ausy.service;

import com.mnc.ausy.domain.Menu;
import com.mnc.ausy.repo.MenuRepository;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * Hak akses sebagai data, bukan daftar group yang di-hardcode (BUG_AUSY_00112/00113/00114).
 *
 * Semua yang bisa dibuka seseorang berasal dari satu tempat: baris {@code group_menu} milik
 * group-nya. Tiga jenis item (kolom {@code menu.kind}):
 * <ul>
 *   <li>{@code MENU}   — halaman: muncul di sidebar dan URL-nya boleh dibuka</li>
 *   <li>{@code ACTION} — endpoint tanpa menu sendiri, mis. "Ajukan Export" yang tidak boleh
 *       ikut terbuka hanya karena group punya halaman List GL Aktif</li>
 *   <li>{@code CARD}   — card/section dashboard ({@code url = 'card:<key>'})</li>
 * </ul>
 *
 * Aturan URL: <b>pemilik terpanjang yang menang</b>. Untuk sebuah path dicari item dengan URL
 * terpanjang yang cocok (sama persis atau diikuti "/"), lalu ditanyakan apakah group memilikinya.
 * Jadi {@code /gl-legacy/export-request} dimiliki ACTION-nya sendiri, bukan menu {@code /gl-legacy},
 * dan {@code /gl-data/bulk-export} ikut menu {@code /gl-data}. Path yang tidak dimiliki item mana
 * pun (dashboard, ganti password, dsb.) terbuka untuk semua yang sudah login — sama seperti
 * aturan {@code anyRequest().authenticated()} sebelumnya.
 */
@Service
public class AccessService {

    private final MenuRepository menus;

    public AccessService(MenuRepository menus) { this.menus = menus; }

    /** Card dashboard yang boleh dilihat group ini (tanpa prefix 'card:'). */
    public Set<String> cardsFor(Integer idGroup) {
        Set<String> out = new LinkedHashSet<>();
        if (idGroup == null) return out;
        for (Menu m : menus.findGrantedItems(idGroup))
            if ("CARD".equals(m.getKind()) && !m.getCardKey().isEmpty()) out.add(m.getCardKey());
        return out;
    }

    /** Boleh atau tidak group ini membuka sebuah path aplikasi (context-path sudah dilepas). */
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
