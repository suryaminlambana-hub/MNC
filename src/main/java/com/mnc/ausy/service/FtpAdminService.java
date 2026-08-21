package com.mnc.ausy.service;

import com.mnc.ausy.domain.ExportTransfer;
import com.mnc.ausy.domain.FtpConfig;
import com.mnc.ausy.repo.ExportTransferRepository;
import com.mnc.ausy.repo.FtpConfigRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/** Admin-managed FTP configuration + transfer queue view (BUG export→FTP flow). */
@Service
public class FtpAdminService {
    private final FtpConfigRepository configRepo;
    private final ExportTransferRepository transfers;
    private final FtpService ftp;
    private final AuditService audit;

    public FtpAdminService(FtpConfigRepository configRepo, ExportTransferRepository transfers,
                           FtpService ftp, AuditService audit) {
        this.configRepo = configRepo; this.transfers = transfers; this.ftp = ftp; this.audit = audit;
    }

    public FtpConfig getConfig() {
        return configRepo.findById(1).orElseGet(() -> { FtpConfig c = new FtpConfig(); c.setId(1); return c; });
    }

    public List<ExportTransfer> recentTransfers() { return transfers.findTop50ByOrderByCreatedAtDesc(); }
    public long pendingCount() { return transfers.countByStatus("PENDING") + transfers.countByStatus("FAILED"); }

    /** Transfers stuck as PENDING/FAILED — need Admin to resend from Konfigurasi FTP. */
    public List<ExportTransfer> needsResend() { return transfers.findByStatusInOrderByCreatedAtDesc(List.of("PENDING", "FAILED")); }

    @Transactional
    public String saveConfig(String host, Integer port, String user, String pass, String dir,
                             boolean secure, boolean passive, boolean enabled, String admin) {
        FtpConfig c = getConfig();
        c.setHost(host==null?"":host.trim());
        c.setPort(port==null?21:port);
        c.setUsername(user==null?"":user.trim());
        // password kosong = jangan ubah (biar admin gak wajib ketik ulang tiap simpan)
        if (pass != null && !pass.isEmpty()) c.setPassword(pass);
        c.setRemoteDir(dir==null?"":dir.trim());
        c.setSecure(secure); c.setPassive(passive); c.setEnabled(enabled);
        c.setUpdatedBy(admin); c.setUpdatedAt(LocalDateTime.now());
        configRepo.save(c);
        audit.transaction(admin, "FTP_CONFIG", "Konfigurasi FTP disimpan (host=" + c.getHost() +
                ", port=" + c.getPort() + ", aktif=" + enabled + ")");
        return "Konfigurasi FTP tersimpan.";
    }

    public String testConnection() {
        try { ftp.test(); return "Koneksi FTP berhasil."; }
        catch (Exception e) { return "Koneksi FTP gagal: " + e.getMessage(); }
    }
}
