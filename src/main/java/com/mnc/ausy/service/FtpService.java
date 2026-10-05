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

    /** Unggah satu berkas (menimpa yang bernama sama); mengembalikan path tujuannya. */
    public String push(Path file) throws Exception {
        FTPClient c = new FTPClient();
        c.setConnectTimeout(10_000);
        c.setDefaultTimeout(30_000);
        try {
            c.connect(host.trim(), port);
            if (!FTPReply.isPositiveCompletion(c.getReplyCode()))
                throw new IllegalStateException("koneksi ditolak: " + c.getReplyString().trim());
            if (!c.login(user, pass))
                throw new IllegalStateException("login gagal untuk user '" + user + "'");
            if (passive) c.enterLocalPassiveMode();
            c.setFileType(FTP.BINARY_FILE_TYPE);
            if (dir != null && !dir.isBlank() && !c.changeWorkingDirectory(dir.trim()))
                throw new IllegalStateException("folder " + dir.trim() + " tidak ada di server FTP");
            String remote = file.getFileName().toString();
            try (InputStream in = Files.newInputStream(file)) {
                if (!c.storeFile(remote, in))
                    throw new IllegalStateException("simpan file ditolak: " + c.getReplyString().trim());
            }
            c.logout();
            return target() + ":" + folder().replaceAll("/+$", "") + "/" + remote;
        } finally {
            try { if (c.isConnected()) c.disconnect(); } catch (Exception ignore) {}
        }
    }
}
