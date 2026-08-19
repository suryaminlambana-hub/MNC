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
 * Configuration is read from the DB (managed by Admin via /admin/ftp); if the DB row has no host,
 * it falls back to the ausy.ftp.* application properties for backward compatibility.
 */
@Service
public class FtpService {
    private final FtpConfigRepository repo;

    // Fallback (deploy-time) properties — used only when the DB config has no host.
    @Value("${ausy.ftp.host:}")        private String pHost;
    @Value("${ausy.ftp.port:21}")      private int pPort;
    @Value("${ausy.ftp.user:}")        private String pUser;
    @Value("${ausy.ftp.pass:}")        private String pPass;
    @Value("${ausy.ftp.dir:}")         private String pDir;
    @Value("${ausy.ftp.secure:false}") private boolean pSecure;
    @Value("${ausy.ftp.passive:true}") private boolean pPassive;

    public FtpService(FtpConfigRepository repo) { this.repo = repo; }

    public record Settings(String host, int port, String user, String pass, String dir,
                           boolean secure, boolean passive, boolean enabled) {}

    private static String n(String s){ return s==null?"":s; }

    /** Effective settings: DB config if a host is set there, otherwise the fallback properties. */
    public Settings settings() {
        FtpConfig c = repo.findById(1).orElse(null);
        if (c != null && c.getHost() != null && !c.getHost().isBlank()) {
            return new Settings(c.getHost(), c.getPort()==null?21:c.getPort(), n(c.getUsername()), n(c.getPassword()),
                    n(c.getRemoteDir()), Boolean.TRUE.equals(c.getSecure()), c.getPassive()==null||c.getPassive(),
                    c.getEnabled()==null||c.getEnabled());
        }
        return new Settings(pHost, pPort, pUser, pPass, pDir, pSecure, pPassive, pHost != null && !pHost.isBlank());
    }

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
                throw new IllegalStateException("Koneksi FTP ditolak: " + c.getReplyString().trim());
            if (!c.login(s.user(), s.pass()))
                throw new IllegalStateException("Login FTP gagal untuk user '" + s.user() + "'");
            c.logout();
        } finally {
            try { if (c.isConnected()) c.disconnect(); } catch (Exception ignore) {}
        }
    }

    /** Upload one file using the current settings; returns the remote path. Throws on any failure. */
    public String push(Path file) throws Exception { return push(file, settings()); }

    public String push(Path file, Settings s) throws Exception {
        FTPClient c = s.secure() ? new FTPSClient() : new FTPClient();
        c.setConnectTimeout(10_000);
        try {
            c.connect(s.host(), s.port());
            if (!FTPReply.isPositiveCompletion(c.getReplyCode()))
                throw new IllegalStateException("Koneksi FTP ditolak: " + c.getReplyString().trim());
            if (!c.login(s.user(), s.pass()))
                throw new IllegalStateException("Login FTP gagal untuk user '" + s.user() + "'");
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
