package com.mnc.ausy.service;

import com.mnc.ausy.domain.GlCode;
import com.mnc.ausy.repo.GlCodeRepository;
import com.mnc.ausy.repo.GlTabelRepository;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Master Kode GL.
 *
 * <b>Tanpa sisi D/C sejak BUG_AUSY_00170 &amp; BUG_AUSY_00171.</b> Sebuah kode dikenali dari
 * nomornya saja, mengikuti daftar COA milik MNC yang juga hanya memuat nomor akun dan namanya.
 * Sisi debit/credit ditentukan baris transaksinya, bukan masternya — maka 100 yang terdaftar
 * berlaku untuk dua-duanya, dan tidak ada lagi 100-D serta 100-C yang hidup terpisah.
 */
@Service
public class GlCodeService {
    private final GlCodeRepository glCodes;
    private final GlTabelRepository glTabel;

    public GlCodeService(GlCodeRepository glCodes, GlTabelRepository glTabel) {
        this.glCodes = glCodes; this.glTabel = glTabel;
    }

    /**
     * NoGL is an account number, so it may only contain digits — BUG_AUSY_00104. The cap follows
     * gl_code.code (VARCHAR(50)) so a value that passes here always fits the column.
     */
    public static boolean validCode(String code) {
        return code != null && code.trim().matches("[0-9]{1,50}");
    }

    /** Kode ini dipakai transaksi atau belum. */
    public boolean used(String code) {
        return usageCount(code) > 0;
    }

    /** Berapa baris GL yang memakai kode ini (baris terhapus tidak dihitung — BUG_AUSY_00123). */
    public long usageCount(String code) {
        if (code == null || code.isBlank()) return 0;
        return glTabel.countUsage(code.trim());
    }

    /** Dari pemakaian itu, berapa yang batch-nya masih menunggu otorisasi upload. */
    public long usagePending(String code) {
        if (code == null || code.isBlank()) return 0;
        return glTabel.countUsagePending(code.trim());
    }

    public boolean used(GlCode g) { return g != null && used(g.getCode()); }

    public GlCode find(Long id) { return glCodes.findById(id).orElse(null); }

    /** Master description for a NoGL, or "" when the code is not registered. */
    public String descriptionOf(String code) {
        if (code == null || code.isBlank()) return "";
        return glCodes.findByCode(code.trim()).map(GlCode::getDescription).orElse("");
    }

    public boolean exists(String code) { return code != null && glCodes.existsByCode(code.trim()); }

    public List<GlCode> listWithUsage() {
        List<GlCode> rows = glCodes.findAllByOrderByCodeAsc();
        for (GlCode g : rows) {
            long n = usageCount(g.getCode());
            g.setUsageCount(n);
            g.setUsagePending(n > 0 ? usagePending(g.getCode()) : 0);
            g.setUsed(n > 0);
        }
        return rows;
    }

    /** @return null on success, or an error key (exists/error). */
    public String add(String code, String desc, Short status, String by) {
        desc = GlTextRule.trim(desc);
        if (code == null || code.isBlank()) return "nocode";
        if (!validCode(code)) return "badcode";
        code = code.trim();
        if (glCodes.existsByCode(code)) return "exists";
        GlCode g = new GlCode();
        g.setCode(code); g.setDescription(desc);
        g.setStatus(status == null ? 1 : status); g.setCreatedBy(by); g.setCreatedDate(LocalDateTime.now());
        glCodes.save(g);
        return null;
    }

    public String edit(Long id, String code, String desc, Short status, String by) {
        desc = GlTextRule.trim(desc);
        GlCode g = glCodes.findById(id).orElse(null);
        if (g == null) return "error";
        if (used(g)) {
            // locked: only description + status
            g.setDescription(desc); g.setStatus(status);
        } else {
            if (code == null || code.isBlank()) return "nocode";
            if (!validCode(code)) return "badcode";
            code = code.trim();
            glCodes.findByCode(code)
                   .filter(other -> !other.getId().equals(id))
                   .ifPresent(o -> { throw new DuplicateGl(); });
            g.setCode(code); g.setDescription(desc); g.setStatus(status);
        }
        g.setModifiedBy(by); g.setModifiedDate(LocalDateTime.now());
        glCodes.save(g);
        return null;
    }

    public String delete(Long id) {
        GlCode g = glCodes.findById(id).orElse(null);
        if (g == null) return "error";
        if (used(g)) return "inuse";
        glCodes.delete(g);
        return null;
    }

    /** Rows a CSV would add, plus how many were duplicates / invalid. Nothing is written. */
    public record CsvParse(List<Map<String,String>> rows, int dup, int bad) {}

    /**
     * Parse only — shared by the direct path and by the maker→checker request path.
     *
     * Template CSV-nya dua kolom: NoGL, Description (BUG_AUSY_00171). Berkas lama yang masih
     * bertiga kolom tetap terbaca: kolom kedua diperiksa, kalau isinya cuma D atau C ia dianggap
     * sisa kolom Debit/Credit dan dilewati, sehingga deskripsi diambil dari kolom ketiga. Tanpa
     * itu, CSV lama akan masuk dengan deskripsi berbunyi "D".
     */
    public CsvParse parseCsv(InputStream in) throws Exception {
        List<Map<String,String>> rows = new ArrayList<>();
        int dup = 0, bad = 0, line = 0;
        try (BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String row;
            while ((row = r.readLine()) != null) {
                line++;
                if (row.isBlank()) continue;
                if (line == 1 && row.toLowerCase().contains("nogl")) continue;   // header
                String[] c = row.split(",", -1);
                String code = GlTextRule.trim(c.length > 0 ? c[0] : "");
                String desc = GlTextRule.trim(c.length > 1 ? c[1] : "");
                if (isSisaKolomDc(desc)) desc = GlTextRule.trim(c.length > 2 ? c[2] : "");
                if (code.isEmpty() || !validCode(code)) { bad++; continue; }
                // Deskripsi berkarakter terlarang ikut dihitung tidak valid (BUG_AUSY_00180),
                // bukan dibersihkan diam-diam — sejalan dengan unggahan Excel yang menolak.
                if (GlTextRule.hasOffending(desc)) { bad++; continue; }
                if (glCodes.existsByCode(code) || listed(rows, code)) { dup++; continue; }
                Map<String,String> m = new LinkedHashMap<>();
                m.put("code", code); m.put("description", desc);
                rows.add(m);
            }
        }
        return new CsvParse(rows, dup, bad);
    }

    /** Kolom kedua berisi bekas penanda Debit/Credit, bukan deskripsi. */
    private static boolean isSisaKolomDc(String v) {
        if (v == null) return false;
        String s = v.trim().toUpperCase();
        return s.equals("D") || s.equals("C") || s.equals("DEBIT") || s.equals("CREDIT") || s.equals("KREDIT");
    }

    private static boolean listed(List<Map<String,String>> rows, String code) {
        return rows.stream().anyMatch(m -> code.equals(m.get("code")));
    }

    /** Insert parsed rows, skipping any that became duplicates meanwhile. @return rows added. */
    public int applyImport(List<Map<String,String>> rows, String by) {
        int added = 0;
        if (rows == null) return 0;
        for (Map<String,String> m : rows) {
            String code = m.get("code");
            if (!validCode(code)) continue;
            if (glCodes.existsByCode(code)) continue;
            GlCode g = new GlCode();
            g.setCode(code); g.setDescription(m.get("description"));
            g.setStatus((short)1); g.setCreatedBy(by); g.setCreatedDate(LocalDateTime.now());
            glCodes.save(g); added++;
        }
        return added;
    }

    /** @return {added, duplicate, invalid} */
    public int[] importCsv(InputStream in, String by) throws Exception {
        CsvParse p = parseCsv(in);
        return new int[]{ applyImport(p.rows(), by), p.dup(), p.bad() };
    }

    public static class DuplicateGl extends RuntimeException {}
}
