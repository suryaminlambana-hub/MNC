package com.mnc.ausy.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Satu-satunya tempat file hasil export ditulis & dibaca dari disk.
 *
 * Pengantaran ke core banking = file terbit di folder ini (root FTP lokal / share yang dipantau
 * core). Karena itu penulisannya tidak boleh terlihat setengah jadi: core bisa saja membaca tepat
 * saat aplikasi masih menulis dan mendapat ACCTXT terpotong. File ditulis ke berkas sementara di
 * folder yang sama lalu dipindahkan secara atomik ke nama aslinya — dari sisi core, file itu
 * muncul utuh atau belum ada sama sekali, tidak pernah separuh.
 *
 * Folder tujuannya {@code AUSY_EXPORT_DIR} (kosong = {@code data/txt} di folder aplikasi).
 */
@Service
public class ExportFileStore {

    private static final Logger LOG = LoggerFactory.getLogger(ExportFileStore.class);
    /** Bawaan di Windows: root FTP lokal IIS, tempat core banking mengambil file. */
    private static final String WINDOWS_DEFAULT_DIR = "C:/inetpub/ftproot";
    /** Bawaan di OS lain (mesin dev): di bawah folder aplikasi. */
    private static final String DEFAULT_DIR = "data/txt";
    /** Akhiran berkas sementara saat menulis; tidak pernah ikut ditampilkan sebagai hasil export. */
    private static final String PART = ".part";

    @Value("${ausy.export-dir:}") private String exportDir;
    @Value("${ausy.txt-dir:}")    private String txtDir;

    /** Satu file hasil export, siap ditampilkan. */
    public record StoredFile(String name, long size, String sizeText, LocalDateTime modified) {
        static StoredFile of(String name, long size, LocalDateTime modified) {
            String t = size < 1024 ? size + " byte"
                     : size < 1024 * 1024 ? String.format("%.1f KB", size / 1024.0)
                     : String.format("%.1f MB", size / (1024.0 * 1024));
            return new StoredFile(name, size, t, modified);
        }
    }

    /**
     * Folder tujuan, selalu absolut.
     *
     * Nilai KOSONG diperlakukan seperti tidak diisi (BUG_AUSY_00151): default placeholder Spring
     * hanya berlaku kalau variabelnya tidak ada sama sekali, sedangkan {@code .env.example} justru
     * mengirim {@code AUSY_EXPORT_DIR=} kosong — hasilnya {@code Paths.get("")}, yaitu direktori
     * kerja proses, dan file mendarat di sana tanpa error apa pun.
     *
     * Setelan yang TIDAK BISA DIPAKAI juga diabaikan: kalau foldernya belum ada dan folder
     * induknya pun tidak ada, itu praktis selalu salah tulis (mis. {@code C:\inethub\ftproot}).
     * Dulu folder salah itu dibuat begitu saja dan hasil export terbit di tempat yang tidak
     * dipantau siapa pun; sekarang dipakai bawaannya, dan sumber yang berlaku disebut di log
     * saat start.
     */
    public Path dir() {
        Path configured = configuredDir();
        if (configured == null) return defaultDir();
        return usable(configured) ? configured : defaultDir();
    }

    /** Isi {@code AUSY_EXPORT_DIR} (atau {@code AUSY_TXT_DIR}) apa adanya; null kalau tidak diisi. */
    Path configuredDir() {
        String v = exportDir == null ? "" : exportDir.trim();
        if (v.isEmpty()) v = txtDir == null ? "" : txtDir.trim();
        return v.isEmpty() ? null : Paths.get(v).toAbsolutePath().normalize();
    }

    /** Folder bawaan: root FTP lokal di Windows, folder aplikasi di OS lain. */
    Path defaultDir() {
        String os = System.getProperty("os.name", "");
        String v = os.toLowerCase().contains("win") ? WINDOWS_DEFAULT_DIR : DEFAULT_DIR;
        return Paths.get(v).toAbsolutePath().normalize();
    }

    /** Folder sudah ada, atau setidaknya folder induknya ada sehingga wajar untuk dibuat. */
    private boolean usable(Path p) {
        if (Files.isDirectory(p)) return true;
        Path parent = p.getParent();
        return parent != null && Files.isDirectory(parent);
    }

    /** Nama file di dalam folder export — nama saja, supaya tidak bisa menunjuk ke luar folder. */
    public Path resolve(String name) {
        String safe = Paths.get(name == null ? "" : name).getFileName().toString();
        if (safe.isEmpty() || safe.equals(".") || safe.equals(".."))
            throw new IllegalArgumentException("Nama file hasil export tidak valid: " + name);
        return dir().resolve(safe);
    }

    /**
     * Tulis satu file hasil export; menimpa file bernama sama. Mengembalikan lokasi akhirnya.
     * Isi ditulis sebagai teks UTF-8 — ACCTXT adalah berkas teks lebar tetap.
     */
    public Path write(String name, String content) throws IOException {
        Path target = resolve(name);
        Path dir = target.getParent();
        Files.createDirectories(dir);
        Path tmp = Files.createTempFile(dir, target.getFileName() + ".", PART);
        try {
            Files.writeString(tmp, content, StandardCharsets.UTF_8);
            makeReadable(tmp);
            try {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                // Sebagian filesystem jaringan tidak mendukungnya; pindahan biasa tetap jauh lebih
                // singkat jendelanya dibanding menulis langsung ke nama aslinya.
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            }
            return target;
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    /**
     * Yang mengambil file ini proses lain (layanan FTP / core banking), sering dengan user berbeda,
     * jadi file hasil export harus bisa dibaca mereka. {@code createTempFile} membuat berkasnya
     * rw------- (hanya pemilik) dan izin itu ikut terbawa saat dipindahkan — tanpa langkah ini
     * file-nya terbit tapi tidak bisa dibaca siapa pun selain user aplikasi.
     *
     * Di Windows tidak ada POSIX permission: berkas sementara dibuat di folder tujuan sehingga
     * mewarisi ACL folder itu, persis seperti file yang dibuat langsung di sana.
     */
    private void makeReadable(Path file) {
        try {
            if (!file.getFileSystem().supportedFileAttributeViews().contains("posix")) return;
            Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-r--r--"));
        } catch (Exception e) {
            LOG.warn("Izin baca file hasil export {} tidak bisa diatur: {}", file, e.getMessage());
        }
    }

    public boolean exists()   { return Files.isDirectory(dir()); }
    public boolean writable() { Path d = dir(); return Files.isDirectory(d) && Files.isWritable(d); }

    /** Isi folder export, terbaru dulu. Berkas sementara tidak ikut. */
    public List<StoredFile> list() {
        Path d = dir();
        List<StoredFile> out = new ArrayList<>();
        if (!Files.isDirectory(d)) return out;
        try (var paths = Files.list(d)) {
            for (Path f : paths.filter(Files::isRegularFile).toList()) {
                String name = f.getFileName().toString();
                if (name.endsWith(PART)) continue;
                LocalDateTime at = LocalDateTime.ofInstant(
                        Files.getLastModifiedTime(f).toInstant(), ZoneId.systemDefault());
                out.add(StoredFile.of(name, Files.size(f), at));
            }
        } catch (Exception e) {
            LOG.warn("Folder hasil export {} tidak bisa dibaca: {}", d, e.getMessage());
        }
        out.sort(Comparator.comparing(StoredFile::modified).reversed());
        return out;
    }

    /**
     * Sebutkan foldernya saat start, sekalian pastikan bisa ditulis.
     *
     * Kegagalan menulis dulu baru ketahuan saat checker menekan approve, dan folder yang salah
     * tulis tidak pernah ketahuan sama sekali — folder itu dibuat begitu saja (BUG_AUSY_00151).
     */
    @jakarta.annotation.PostConstruct
    void logDir() {
        Path configured = configuredDir();
        Path d = dir();
        if (configured != null && !configured.equals(d))
            LOG.warn("AUSY_EXPORT_DIR menunjuk {} yang tidak bisa dipakai (folder itu dan folder"
                    + " induknya tidak ada — biasanya salah tulis). Dipakai folder bawaan {}."
                    + " Perbaiki AUSY_EXPORT_DIR kalau maksudnya folder lain.", configured, d);
        boolean isNew = !Files.isDirectory(d);
        try {
            Files.createDirectories(d);
            if (!Files.isWritable(d))
                LOG.warn("Folder hasil export {} TIDAK BISA DITULIS — file export akan gagal dibuat."
                        + " Cek izin folder / setelan AUSY_EXPORT_DIR.", d);
            else if (isNew)
                LOG.info("File hasil export ditulis ke {} (folder baru dibuat)", d);
            else
                LOG.info("File hasil export ditulis ke {}", d);
        } catch (Exception e) {
            LOG.warn("Folder hasil export {} tidak bisa disiapkan: {}. Cek AUSY_EXPORT_DIR & izin folder.",
                    d, e.getMessage());
        }
    }
}
