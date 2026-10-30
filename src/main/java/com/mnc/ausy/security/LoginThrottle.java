package com.mnc.ausy.security;

import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Pembatasan percobaan login (REQ_AUSY_00070 — pentest: tidak ada rate limit, terbuka untuk brute
 * force). Dulu login boleh dicoba tanpa batas.
 *
 * <ul>
 *   <li>Per username: {@value #MAKS_GAGAL_USER} kali gagal dalam {@link #JENDELA} → username itu
 *       dikunci selama {@link #KUNCI}, juga untuk password yang benar.</li>
 *   <li>Per alamat IP: {@value #MAKS_GAGAL_IP} kali gagal → IP itu dikunci, supaya penyerang tidak
 *       bisa sekadar berganti-ganti username. Batasnya longgar karena banyak user bisa tampil
 *       dari IP proxy yang sama.</li>
 * </ul>
 * Login berhasil menghapus hitungan username itu. Disimpan di memori: restart mengosongkannya,
 * dan itu dapat diterima untuk kunci 15 menit.
 */
@Component
public class LoginThrottle {
    static final int MAKS_GAGAL_USER = 5;
    static final int MAKS_GAGAL_IP = 30;
    static final Duration JENDELA = Duration.ofMinutes(15);
    static final Duration KUNCI = Duration.ofMinutes(15);

    private final Clock clock;
    private final Map<String, Deque<Instant>> gagal = new ConcurrentHashMap<>();
    private final Map<String, Instant> terkunci = new ConcurrentHashMap<>();

    public LoginThrottle() { this(Clock.systemUTC()); }
    LoginThrottle(Clock clock) { this.clock = clock; }

    private static String kunciUser(String u) { return "u:" + (u == null ? "" : u.trim().toLowerCase(Locale.ROOT)); }
    private static String kunciIp(String ip) { return "ip:" + (ip == null ? "" : ip); }

    /** Sisa waktu terkunci untuk username atau IP ini, atau {@link Duration#ZERO} bila boleh mencoba. */
    public Duration sisaKunci(String username, String ip) {
        Duration a = sisa(kunciUser(username)), b = sisa(kunciIp(ip));
        return a.compareTo(b) >= 0 ? a : b;
    }

    public void gagal(String username, String ip) {
        catat(kunciUser(username), MAKS_GAGAL_USER);
        catat(kunciIp(ip), MAKS_GAGAL_IP);
    }

    public void berhasil(String username) {
        gagal.remove(kunciUser(username));
        terkunci.remove(kunciUser(username));
    }

    private Duration sisa(String k) {
        Instant sampai = terkunci.get(k);
        if (sampai == null) return Duration.ZERO;
        Duration d = Duration.between(clock.instant(), sampai);
        if (d.isNegative() || d.isZero()) { terkunci.remove(k); return Duration.ZERO; }
        return d;
    }

    private void catat(String k, int maks) {
        Instant now = clock.instant();
        Deque<Instant> q = gagal.computeIfAbsent(k, x -> new ArrayDeque<>());
        synchronized (q) {
            while (!q.isEmpty() && q.peekFirst().isBefore(now.minus(JENDELA))) q.pollFirst();
            q.addLast(now);
            if (q.size() >= maks) { terkunci.put(k, now.plus(KUNCI)); q.clear(); }
        }
        // Bersih-bersih sesekali supaya peta tidak tumbuh tanpa batas oleh username acak.
        if (gagal.size() > 10_000) gagal.entrySet().removeIf(e -> {
            synchronized (e.getValue()) {
                return e.getValue().isEmpty() || e.getValue().peekLast().isBefore(now.minus(JENDELA));
            }
        });
    }
}
