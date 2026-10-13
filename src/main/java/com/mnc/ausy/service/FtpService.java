package com.mnc.ausy.service;

import org.apache.commons.net.ftp.FTP;
import org.apache.commons.net.ftp.FTPClient;
import org.apache.commons.net.ftp.FTPReply;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.UUID;

/**
 * Kirim berkas ACCTXT ke server FTP MNC setelah ditulis (BUG_AUSY_00189).
 *
 * FTP MNC ada di mesin lain (server DB, 10.5.99.23), bukan di mesin aplikasi, jadi menulis ke
 * folder lokal saja tidak pernah sampai ke sana. Setelan hanya dari .env ({@code FTP_HOST},
 * {@code FTP_USER}, {@code FTP_PASS}, {@code FTP_DIR}, {@code FTP_PORT}); tidak ada layar
 * maupun tabel setelan — dulu baris peninggalan di tabel itulah yang diam-diam menimpa
 * kredensial (BUG_AUSY_00141). {@code FTP_HOST} kosong = pengiriman mati, berkas cukup di folder
 * lokal seperti sebelumnya.
 */
@Service
public class FtpService {

    private static final Logger LOG = LoggerFactory.getLogger(FtpService.class);

    @Value("${ausy.ftp.host:}")        private String host;
    @Value("${ausy.ftp.port:21}")      private int port;
    @Value("${ausy.ftp.user:}")        private String user;
    @Value("${ausy.ftp.pass:}")        private String pass;
    @Value("${ausy.ftp.dir:}")         private String dir;
    @Value("${ausy.ftp.passive:true}") private boolean passive;

    public boolean enabled() { return host != null && !host.isBlank(); }

    /** Tujuan untuk pesan di layar, mis. {@code ftpuser@10.5.99.23}. */
    public String target() { return user + "@" + host.trim(); }

    /**
     * Folder tujuan seperti yang dilihat tim infra di server FTP, mis. {@code /home/ftpuser} —
     * bukan "/" yang dilihat user FTP di dalam chroot-nya. {@code FTP_DIR} kosong = folder awal
     * user FTP, yaitu home-nya.
     */
    public String folder() { return dir == null || dir.isBlank() ? "/home/" + user : dir.trim(); }

    @jakarta.annotation.PostConstruct
    void logTarget() {
        if (enabled()) LOG.info("File ACCTXT dikirim ke FTP {}:{} setelah ditulis", target(), folder());
        else LOG.info("Pengiriman FTP mati (FTP_HOST kosong): file ACCTXT hanya ditulis ke folder lokal");
    }

    /**
     * Berapa kali unggahan diulang sebelum menyerah, dan jeda antar-percobaannya.
     *
     * Berkas ACCTXT berada di folder yang juga dibaca pihak lain — core banking yang menjemput,
     * pemindai virus, atau layanan FTP itu sendiri. Di Windows, satu proses yang sedang memegang
     * berkasnya membuat proses lain ditolak dengan 550 "being used by another process", dan
     * pegangan semacam itu biasanya lepas dalam hitungan detik. Sekali coba lalu menyerah
     * mengubah gangguan sesaat menjadi kegagalan penyusunan yang harus diulang orang.
     */
    private static final int PERCOBAAN = 3;
    private static final long[] JEDA_MS = {2_000, 5_000};

    /** Unggah satu berkas (menimpa yang bernama sama); mengembalikan path tujuannya. */
    public String push(Path file) throws Exception {
        String remote = file.getFileName().toString();
        Exception terakhir = null;

        for (int ke = 1; ke <= PERCOBAAN; ke++) {
            FTPClient c = new FTPClient();
            c.setConnectTimeout(10_000);
            c.setDefaultTimeout(30_000);
            try {
                sambung(c);

                // Kalau folder FTP tujuan TERNYATA folder export mesin ini, berkasnya sudah
                // berada di tempat yang benar sejak ditulis dan tidak ada yang perlu diunggah.
                // Memaksakan unggahan justru yang menimbulkan 550 di Windows: proses ini membuka
                // berkasnya untuk dibaca, sementara layanan FTP membuka berkas YANG SAMA untuk
                // ditimpa. Keduanya satu berkas, jadi Windows menolak salah satunya.
                if (folderTujuanSamaDenganLokal(c, file.getParent())) {
                    LOG.info("Folder FTP {} adalah folder export mesin ini — {} sudah berada di"
                            + " tujuan sejak ditulis, unggahan dilewati", folder(), remote);
                    return tujuan(remote);
                }

                unggah(c, file, remote);
                if (ke > 1) LOG.info("Unggahan {} berhasil pada percobaan ke-{}", remote, ke);
                c.logout();
                return tujuan(remote);
            } catch (Exception e) {
                terakhir = e;
                if (ke < PERCOBAAN && bisaDicobaLagi(e)) {
                    LOG.warn("Unggahan {} gagal pada percobaan ke-{} ({}), diulang sebentar lagi",
                            remote, ke, e.getMessage());
                    Thread.sleep(JEDA_MS[ke - 1]);
                } else {
                    throw perjelas(e, remote);
                }
            } finally {
                try { if (c.isConnected()) c.disconnect(); } catch (Exception ignore) {}
            }
        }
        throw perjelas(terakhir, remote);  // tidak tercapai; penenang compiler
    }

    private void sambung(FTPClient c) throws Exception {
        c.connect(host.trim(), port);
        if (!FTPReply.isPositiveCompletion(c.getReplyCode()))
            throw new IllegalStateException("koneksi ditolak: " + c.getReplyString().trim());
        if (!c.login(user, pass))
            throw new IllegalStateException("login gagal untuk user '" + user + "'");
        if (passive) c.enterLocalPassiveMode();
        c.setFileType(FTP.BINARY_FILE_TYPE);
        if (dir != null && !dir.isBlank() && !c.changeWorkingDirectory(dir.trim()))
            throw new IllegalStateException("folder " + dir.trim() + " tidak ada di server FTP");
    }

    /**
     * Unggah dari SALINAN di folder sementara sistem, bukan dari berkas aslinya.
     *
     * Selama unggahan, berkas sumber dipegang terbuka untuk dibaca. Kalau yang dipegang adalah
     * berkas di folder export, pihak lain yang menjemput atau menimpa berkas itu pada saat yang
     * sama akan ditolak Windows. Membaca dari salinan membuat berkas yang di folder export tidak
     * pernah disentuh proses ini sesudah ditulis.
     */
    private void unggah(FTPClient c, Path file, String remote) throws Exception {
        Path salinan = Files.createTempFile("ausy-ftp-", "-" + remote);
        try {
            Files.copy(file, salinan, StandardCopyOption.REPLACE_EXISTING);
            try (InputStream in = Files.newInputStream(salinan)) {
                if (!c.storeFile(remote, in))
                    throw new IllegalStateException("simpan file ditolak: " + c.getReplyString().trim());
            }
        } finally {
            try { Files.deleteIfExists(salinan); } catch (Exception ignore) {}
        }
    }

    /**
     * Apakah folder kerja di server FTP itu folder yang sama dengan {@code lokal} di mesin ini?
     *
     * Dibuktikan, bukan ditebak dari alamat IP: sebuah berkas penanda bernama acak dibuat di
     * folder lokal, lalu daftar isi folder di server FTP diperiksa. Kalau penandanya muncul di
     * sana, keduanya memang satu folder. Cara ini tetap benar walau FTP-nya menunjuk localhost,
     * alamat LAN mesin sendiri, atau folder yang dibagikan lewat jaringan.
     *
     * Gagal memeriksa tidak dianggap "sama" — unggahannya tetap dijalankan seperti biasa.
     */
    private boolean folderTujuanSamaDenganLokal(FTPClient c, Path lokal) {
        if (lokal == null) return false;
        Path penanda = lokal.resolve("ausy-cek-" + UUID.randomUUID() + ".tmp");
        try {
            Files.createFile(penanda);
            String nama = penanda.getFileName().toString();
            String[] isi = c.listNames();
            if (isi == null) return false;   // server menolak LIST: anggap folder lain
            for (String ada : isi) {
                if (ada == null) continue;
                // Sebagian server menjawab dengan path, bukan nama saja.
                String bersih = ada.substring(ada.lastIndexOf('/') + 1);
                if (bersih.equals(nama)) return true;
            }
            return false;
        } catch (Exception e) {
            LOG.debug("Pemeriksaan folder FTP dilewati: {}", e.toString());
            return false;
        } finally {
            try { Files.deleteIfExists(penanda); } catch (Exception ignore) {}
        }
    }

    /** Kegagalan yang pantas dicoba lagi: berkasnya sedang dipegang proses lain, atau jaringan. */
    private boolean bisaDicobaLagi(Exception e) {
        String p = String.valueOf(e.getMessage()).toLowerCase();
        return p.contains("being used by another process")
            || p.contains("sedang digunakan")
            || p.contains("access is denied")
            || p.contains("timed out")
            || e instanceof java.io.IOException;
    }

    /** Tambahkan sebabnya yang paling mungkin ke pesan yang dibaca orang di layar. */
    private Exception perjelas(Exception e, String remote) {
        String p = String.valueOf(e.getMessage());
        if (!p.toLowerCase().contains("being used by another process")) return e;
        return new IllegalStateException(p + " — berkas " + remote + " di server FTP sedang"
                + " dipegang proses lain (core banking yang menjemput, pemindai virus, atau"
                + " unggahan sebelumnya yang belum selesai). Sudah dicoba " + PERCOBAAN + " kali."
                + " Pastikan tidak ada yang membuka berkas itu, lalu jalankan lagi.", e);
    }

    private String tujuan(String remote) {
        return target() + ":" + folder().replaceAll("/+$", "") + "/" + remote;
    }
}
