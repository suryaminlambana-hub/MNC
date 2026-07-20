package com.mnc.ausy.service;

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
 * Transfer-out of generated GLTRRF files to the GL FTP/FTPS server (Process Data Flow §9.g).
 * No-op unless ausy.ftp.host is configured — in dev the file is still written locally.
 */
@Service
public class FtpService {
    @Value("${ausy.ftp.host:}")        private String host;
    @Value("${ausy.ftp.port:21}")      private int port;
    @Value("${ausy.ftp.user:}")        private String user;
    @Value("${ausy.ftp.pass:}")        private String pass;
    @Value("${ausy.ftp.dir:}")         private String dir;
    @Value("${ausy.ftp.secure:false}") private boolean secure;   // FTPS (FTP over TLS)
    @Value("${ausy.ftp.passive:true}") private boolean passive;

    /** FTP push is active only when a host is configured. */
    public boolean enabled() { return host != null && !host.isBlank(); }

    /** Upload one file to the FTP server; returns the remote path. Throws on any failure. */
    public String push(Path file) throws Exception {
        FTPClient c = secure ? new FTPSClient() : new FTPClient();
        c.setConnectTimeout(10_000);
        try {
            c.connect(host, port);
            if (!FTPReply.isPositiveCompletion(c.getReplyCode()))
                throw new IllegalStateException("Koneksi FTP ditolak: " + c.getReplyString().trim());
            if (!c.login(user, pass))
                throw new IllegalStateException("Login FTP gagal untuk user '" + user + "'");
            if (secure) { ((FTPSClient) c).execPBSZ(0); ((FTPSClient) c).execPROT("P"); }
            if (passive) c.enterLocalPassiveMode();
            c.setFileType(FTP.BINARY_FILE_TYPE);
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
