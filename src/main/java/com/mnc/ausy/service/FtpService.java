package com.mnc.ausy.service;

import com.mnc.ausy.domain.FtpConfig;
import com.mnc.ausy.repo.FtpConfigRepository;
import org.apache.commons.net.ftp.FTP;
import org.apache.commons.net.ftp.FTPClient;
import org.apache.commons.net.ftp.FTPReply;
import org.apache.commons.net.ftp.FTPSClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Transfer-out of generated GLTRRF/ACCTXT files to the GL FTP/FTPS server (Process Data Flow §9.g).
 *
 * Setelan dibaca dari properti deployment {@code ausy.ftp.*} (FTP_* di .env), bukan lewat website —
 * layar "Konfigurasi FTP" sudah dihapus (BUG_AUSY_00141). Baris {@code ftp_config} peninggalan layar
 * itu masih bisa dipakai, tapi harus diminta dengan {@code FTP_ENABLED=true}; lihat {@link #settings()}.
 */
@Service
public class FtpService {
    private final FtpConfigRepository repo;

    /**
     * Saklar tegas untuk push FTP:
     * <ul>
     *   <li>{@code false} — mati, apa pun isi sumber lain.</li>
     *   <li>{@code true} — hidup; boleh memakai baris {@code ftp_config} kalau {@code FTP_HOST} kosong.</li>
     *   <li>kosong — {@code FTP_HOST} kalau diisi, kalau tidak: mati (baris {@code ftp_config} diabaikan).</li>
     * </ul>
     * Nilai kosong sengaja mati, bukan "cari sumber lain": deployment yang mengirim lewat direktori
     * export (BUG_AUSY_00146) tidak mengisi {@code FTP_*} sama sekali, dan dulu justru di situ baris
     * peninggalan layar lama mengambil alih dan menggagalkan setiap export.
     */
    @Value("${ausy.ftp.enabled:}")     private String pEnabled;

    // Deploy-time properties.
    @Value("${ausy.ftp.host:}")        private String pHost;
    @Value("${ausy.ftp.port:21}")      private int pPort;
    @Value("${ausy.ftp.user:}")        private String pUser;
    @Value("${ausy.ftp.pass:}")        private String pPass;
    @Value("${ausy.ftp.dir:}")         private String pDir;
    @Value("${ausy.ftp.secure:false}") private boolean pSecure;
    @Value("${ausy.ftp.passive:true}") private boolean pPassive;

    public FtpService(FtpConfigRepository repo) { this.repo = repo; }

    /** {@code source} = dari mana setelan ini dibaca, supaya bisa disebut saat ada kegagalan. */
    public record Settings(String host, int port, String user, String pass, String dir,
                           boolean secure, boolean passive, boolean enabled, String source) {}

    private static String n(String s){ return s==null?"":s; }

    /**
     * Setelan yang berlaku. <b>{@code FTP_HOST} di .env menang</b>; baris {@code ftp_config} di
     * database hanya dipakai kalau operator memintanya dengan tegas ({@code FTP_ENABLED=true}).
     *
     * Urutannya dulu terbalik — baris database menang — dan itu aman selama masih ada layar
     * "Konfigurasi FTP" untuk melihat serta mengubahnya. Setelah layar itu dihapus (BUG_AUSY_00141)
     * urutan lama jadi jebakan: sisa baris dari layar lama terus menimpa .env, sementara tidak ada
     * satu pun tempat di aplikasi yang bisa menunjukkan isinya. Yang terlihat cuma pesan gagal
     * menyebut username yang tidak pernah ditulis siapa pun di .env.
     *
     * Menjadikan .env menang saja belum menutup jebakannya: di deployment yang {@code FTP_*}-nya
     * memang dibiarkan kosong (kirim lewat direktori export, BUG_AUSY_00146) baris peninggalan itu
     * tetap yang dipakai, dan SETIAP otorisasi export gagal dengan kredensial yang tidak bisa
     * diperbaiki dari mana pun di website. Karena itu baris database sekarang harus diminta:
     * kosong = FTP mati dan baris lama diabaikan (disebutkan di log start & card beranda Admin),
     * {@code FTP_ENABLED=true} = pakai baris itu. Deployment yang mengisi {@code FTP_HOST} di .env
     * tidak berubah perilakunya.
     */
    public Settings settings() {
        if (forcedOff())
            return new Settings("", pPort, "", "", pDir, pSecure, pPassive, false, SRC_OFF);
        if (pHost != null && !pHost.isBlank())
            return new Settings(pHost, pPort, pUser, pPass, pDir, pSecure, pPassive, true, SRC_ENV);
        FtpConfig c = repo.findById(1).orElse(null);
        boolean rowUsable = c != null && c.getHost() != null && !c.getHost().isBlank();
        if (rowUsable && forcedOn()) {
            return new Settings(c.getHost(), c.getPort()==null?21:c.getPort(), n(c.getUsername()), n(c.getPassword()),
                    n(c.getRemoteDir()), Boolean.TRUE.equals(c.getSecure()), c.getPassive()==null||c.getPassive(),
                    c.getEnabled()==null||c.getEnabled(), SRC_DB);
        }
        if (rowUsable)   // ada, tapi tidak diminta — jangan dipakai diam-diam
            return new Settings("", pPort, "", "", pDir, pSecure, pPassive, false, SRC_DB_IGNORED);
        return new Settings("", pPort, pUser, pPass, pDir, pSecure, pPassive, false, SRC_NONE);
    }

    // Ditulis dengan bahasa biasa: label ini tampil di halaman yang dibuka petugas bank, bukan
    // hanya di log. Petunjuk teknis cara mengubahnya ada di log saat start & dokumen deploy.
    private static final String SRC_ENV  = "konfigurasi server";
    private static final String SRC_DB   = "database (tabel ftp_config)";
    private static final String SRC_NONE = "belum diatur — pengiriman FTP tidak aktif";
    private static final String SRC_OFF  = "dimatikan di konfigurasi server";
    private static final String SRC_DB_IGNORED = "belum diatur di konfigurasi server — pengiriman FTP tidak aktif";

    /** {@code FTP_ENABLED} diisi tegas "false"/"0"/"no"/"off". Kosong = tidak memaksa. */
    private boolean forcedOff() { return is(pEnabled, "false", "0", "no", "off"); }

    /** {@code FTP_ENABLED} diisi tegas "true"/"1"/"yes"/"on" — izin memakai baris {@code ftp_config}. */
    private boolean forcedOn() { return is(pEnabled, "true", "1", "yes", "on"); }

    private boolean is(String v, String... values) {
        String s = v == null ? "" : v.trim().toLowerCase();
        for (String x : values) if (s.equals(x)) return true;
        return false;
    }

    /** Dari mana setelan FTP dibaca saat ini — dicatat di log start & disebut di pesan gagal. */
    public String sourceLabel() { return settings().source(); }

    /**
     * Sebutkan sumbernya di log ketika aplikasi naik. Tanpa ini, satu-satunya petunjuk kalau
     * setelan datang dari tempat yang tidak diduga adalah pesan gagal login (BUG_AUSY_00141).
     */
    @jakarta.annotation.PostConstruct
    void logSource() {
        Settings s = settings();
        if (SRC_DB_IGNORED.equals(s.source())) {
            LOG.warn("Push FTP MATI: FTP_HOST tidak diisi di .env, dan baris ftp_config (id=1) diabaikan."
                    + " File ACCTXT cukup ditulis ke direktori export dan diambil core dari sana."
                    + " Kalau memang harus dikirim lewat FTP, isi FTP_HOST/FTP_USER/FTP_PASS di .env,"
                    + " atau FTP_ENABLED=true untuk memakai baris ftp_config itu.");
            return;
        }
        LOG.info("Setelan FTP dibaca dari {}{}", s.source(),
                s.host().isBlank() ? "" : " — host=" + s.host() + ", user=" + s.user());
    }

    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(FtpService.class);

    /** FTP push is active only when a host is configured AND the config is enabled. */
    public boolean enabled() {
        Settings s = settings();
        return s.enabled() && s.host() != null && !s.host().isBlank();
    }

    /** Connect + login + logout using the current settings; throws on any failure. */
    public void test() throws Exception {
        Settings s = settings();
        if (s.host() == null || s.host().isBlank()) throw new IllegalStateException("Host FTP belum diisi.");
        FTPClient c = s.secure() ? new FTPSClient() : new FTPClient();
        c.setConnectTimeout(10_000);
        try {
            c.connect(s.host(), s.port());
            if (!FTPReply.isPositiveCompletion(c.getReplyCode()))
                throw new IllegalStateException("Koneksi FTP ditolak: " + c.getReplyString().trim() + from(s));
            if (!c.login(s.user(), s.pass()))
                throw new IllegalStateException("Login FTP gagal untuk user '" + s.user() + "'" + from(s));
            c.logout();
        } finally {
            try { if (c.isConnected()) c.disconnect(); } catch (Exception ignore) {}
        }
    }

    /** Sumber setelan, ditempel ke pesan gagal supaya jelas nilai itu datang dari mana. */
    private static String from(Settings s) {
        return " (setelan dari " + s.source() + ", host " + s.host() + ")";
    }

    /** Upload one file using the current settings; returns the remote path. Throws on any failure. */
    public String push(Path file) throws Exception { return push(file, settings()); }

    /**
     * Sumber setelan ikut disebut untuk SEMUA kegagalan, bukan cuma yang kita lempar sendiri:
     * pesan mentah dari library ("Timed out waiting for initial connect reply") tidak memberi
     * petunjuk host/kredensialnya datang dari .env atau dari baris ftp_config (BUG_AUSY_00141).
     */
    public String push(Path file, Settings s) throws Exception {
        try {
            return doPush(file, s);
        } catch (Exception e) {
            String m = e.getMessage() == null || e.getMessage().isBlank() ? e.getClass().getSimpleName() : e.getMessage();
            if (m.contains(s.source())) throw e;
            throw new IllegalStateException(m + from(s), e);
        }
    }

    private String doPush(Path file, Settings s) throws Exception {
        FTPClient c = s.secure() ? new FTPSClient() : new FTPClient();
        c.setConnectTimeout(10_000);
        try {
            c.connect(s.host(), s.port());
            if (!FTPReply.isPositiveCompletion(c.getReplyCode()))
                throw new IllegalStateException("Koneksi FTP ditolak: " + c.getReplyString().trim() + from(s));
            if (!c.login(s.user(), s.pass()))
                throw new IllegalStateException("Login FTP gagal untuk user '" + s.user() + "'" + from(s));
            if (s.secure()) { ((FTPSClient) c).execPBSZ(0); ((FTPSClient) c).execPROT("P"); }
            if (s.passive()) c.enterLocalPassiveMode();
            c.setFileType(FTP.BINARY_FILE_TYPE);
            String dir = s.dir();
            if (dir != null && !dir.isBlank() && !c.changeWorkingDirectory(dir)) {
                c.makeDirectory(dir);
                c.changeWorkingDirectory(dir);
            }
            String remote = file.getFileName().toString();
            try (InputStream in = Files.newInputStream(file)) {
                if (!c.storeFile(remote, in))
                    throw new IllegalStateException("Store file gagal: " + c.getReplyString().trim());
            }
            c.logout();
            return (dir == null || dir.isBlank() ? "" : dir.replaceAll("/+$", "") + "/") + remote;
        } finally {
            try { if (c.isConnected()) c.disconnect(); } catch (Exception ignore) {}
        }
    }
}
