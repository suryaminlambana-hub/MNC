package com.mnc.ausy.service;

import com.mnc.ausy.domain.NalaUpload;
import com.mnc.ausy.repo.NalaUploadRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.*;

@Service
public class NalaUploadService {
    private final NalaUploadRepository repo;
    private final UploadService uploadService;

    @Value("${ausy.nala-dir:data/nala_temp}") private String baseDir;

    public NalaUploadService(NalaUploadRepository repo, UploadService uploadService) {
        this.repo = repo; this.uploadService = uploadService;
    }

    private static String sha256Hex(byte[] b) throws Exception {
        byte[] h = MessageDigest.getInstance("SHA-256").digest(b);
        StringBuilder sb = new StringBuilder();
        for (byte x : h) sb.append(String.format("%02x", x));
        return sb.toString();
    }
    private Path dir(String uploadId) { return Paths.get(baseDir, uploadId); }

    @Transactional
    public Map<String,Object> init(String user, String name, long size, int totalChunks, String checksum) throws Exception {
        String uploadId = sha256Hex((user + name + size + checksum).getBytes());
        NalaUpload u = repo.findByUploadId(uploadId).orElse(null);
        if (u != null && ("done".equals(u.getStatus()) || "processing".equals(u.getStatus()))) {
            return Map.of("upload_id", uploadId, "chunks_received", u.getChunksReceived(), "status", u.getStatus(), "resume", false);
        }
        if (u == null) {
            u = new NalaUpload();
            u.setUploadId(uploadId); u.setUserId(user); u.setOriginalName(name); u.setFileSize(size);
            u.setTotalChunks(totalChunks); u.setChecksum(checksum); u.setStatus("uploading");
            u.setChunksReceived(0); u.setTempPath(dir(uploadId).toString());
            u.setCreatedAt(LocalDateTime.now()); u.setUpdatedAt(LocalDateTime.now());
            repo.save(u);
            Files.createDirectories(dir(uploadId));
            return Map.of("upload_id", uploadId, "chunks_received", 0, "status", "uploading", "resume", false);
        }
        int onDisk = countChunks(uploadId);
        u.setChunksReceived(onDisk); repo.save(u);
        return Map.of("upload_id", uploadId, "chunks_received", onDisk, "status", u.getStatus(), "resume", onDisk > 0);
    }

    private int countChunks(String uploadId) {
        File d = dir(uploadId).toFile();
        File[] f = d.listFiles((dr, n) -> n.startsWith("chunk_"));
        return f == null ? 0 : f.length;
    }

    @Transactional
    public Map<String,Object> receiveChunk(String uploadId, int index, byte[] data) throws Exception {
        NalaUpload u = repo.findByUploadId(uploadId).orElseThrow();
        Files.createDirectories(dir(uploadId));
        Files.write(dir(uploadId).resolve("chunk_" + index), data);
        int recv = countChunks(uploadId);
        u.setChunksReceived(recv); u.setUpdatedAt(LocalDateTime.now());
        boolean complete = recv >= u.getTotalChunks();
        if (complete) u.setStatus("complete");
        repo.save(u);
        return Map.of("ok", true, "chunks_received", recv, "total_chunks", u.getTotalChunks(), "complete", complete);
    }

    public Map<String,Object> status(String uploadId) {
        NalaUpload u = repo.findByUploadId(uploadId).orElseThrow();
        Map<String,Object> m = new HashMap<>();
        m.put("upload_id", uploadId); m.put("status", u.getStatus());
        m.put("chunks_received", u.getChunksReceived()); m.put("total_chunks", u.getTotalChunks());
        m.put("result", u.getResultMsg());
        return m;
    }

    /** Combine chunks, verify checksum, then kick off async processing. */
    @Transactional
    public Map<String,Object> finalizeUpload(String uploadId) throws Exception {
        NalaUpload u = repo.findByUploadId(uploadId).orElseThrow();
        if ("done".equals(u.getStatus())) return Map.of("ok", true, "already_done", true);

        String ts = LocalDateTime.now().toString().replaceAll("[^0-9]", "").substring(0, 14);
        Path finalPath = Paths.get(baseDir, ts + "_" + u.getOriginalName());
        try (OutputStream out = Files.newOutputStream(finalPath)) {
            for (int i = 0; i < u.getTotalChunks(); i++) {
                Path c = dir(uploadId).resolve("chunk_" + i);
                if (!Files.exists(c)) return Map.of("ok", false, "error", "Chunk " + i + " hilang.");
                Files.copy(c, out);
            }
        }
        if (u.getChecksum() != null && !u.getChecksum().isBlank()) {
            String actual = sha256Hex(Files.readAllBytes(finalPath));
            if (!actual.equalsIgnoreCase(u.getChecksum())) {
                u.setStatus("failed"); u.setResultMsg("Checksum mismatch — file corrupted"); repo.save(u);
                Files.deleteIfExists(finalPath);
                return Map.of("ok", false, "error", "Checksum mismatch — file corrupted");
            }
        }
        u.setFinalPath(finalPath.toString()); u.setStatus("complete"); u.setUpdatedAt(LocalDateTime.now());
        repo.save(u);
        return Map.of("ok", true, "final_path", finalPath.toString());
    }

    /**
     * Asynchronous backend processing: parse the combined file into GL.
     * NOT @Transactional itself — uploadService.process() owns its own transaction,
     * so if parsing fails, its tx rolls back cleanly and we can still persist a
     * 'failed' status here (a shared tx would be rollback-only and lose the update).
     */
    @Async
    public void processAsync(String uploadId) {
        NalaUpload u = repo.findByUploadId(uploadId).orElse(null);
        if (u == null || u.getFinalPath() == null) return;
        u.setStatus("processing"); repo.save(u);
        try (InputStream in = Files.newInputStream(Paths.get(u.getFinalPath()))) {
            UploadResult r = uploadService.process(in, u.getOriginalName(), u.getUserId());
            u.setStatus(r.success ? "done" : "failed");
            u.setResultMsg(r.message);
            u.setCompletedAt(LocalDateTime.now());
        } catch (Exception e) {
            u.setStatus("failed"); u.setResultMsg("Gagal memproses: " + e.getMessage());
        }
        repo.save(u);
        // clean chunk dir
        try { for (File f : dir(uploadId).toFile().listFiles()) f.delete(); dir(uploadId).toFile().delete(); } catch (Exception ignore) {}
    }
}
