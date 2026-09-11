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

@Service
public class GlCodeService {
    private final GlCodeRepository glCodes;
    private final GlTabelRepository glTabel;

    public GlCodeService(GlCodeRepository glCodes, GlTabelRepository glTabel) {
        this.glCodes = glCodes; this.glTabel = glTabel;
    }

    public static String normPos(String p) {
        if (p == null) return "";
        p = p.trim().toUpperCase();
        if (p.equals("D") || p.startsWith("DEB")) return "D";
        if (p.equals("C") || p.startsWith("CRE") || p.startsWith("KRE")) return "C";
        return "";
    }

    /**
     * NoGL is an account number, so it may only contain digits — BUG_AUSY_00104. The cap follows
     * gl_code.code (VARCHAR(50)) so a value that passes here always fits the column.
     */
    public static boolean validCode(String code) {
        return code != null && code.trim().matches("[0-9]{1,50}");
    }

    /**
     * A GL code counts as used only on the side (D/C) it was actually posted on — BUG_AUSY_00100:
     * if 100-D appears in a transaction, 100-C is still free to edit or delete.
     */
    public boolean used(String code, String position) {
        return usageCount(code, position) > 0;
    }

    /** Berapa baris GL yang memakai kode ini (baris terhapus tidak dihitung — BUG_AUSY_00123). */
    public long usageCount(String code, String position) {
        String p = normPos(position);
        if (code == null || code.isBlank() || p.isEmpty()) return 0;
        return glTabel.countUsage(code.trim(), p);
    }

    /** Dari pemakaian itu, berapa yang batch-nya masih menunggu otorisasi upload. */
    public long usagePending(String code, String position) {
        String p = normPos(position);
        if (code == null || code.isBlank() || p.isEmpty()) return 0;
        return glTabel.countUsagePending(code.trim(), p);
    }
    public boolean used(GlCode g) { return g != null && used(g.getCode(), g.getPosition()); }

    public GlCode find(Long id) { return glCodes.findById(id).orElse(null); }

    /** Master description for a NoGL + D/C, or "" when the code is not registered. */
    public String descriptionOf(String code, String position) {
        String p = normPos(position);
        if (code == null || code.isBlank() || p.isEmpty()) return "";
        return glCodes.findByCodeAndPosition(code.trim(), p).map(GlCode::getDescription).orElse("");
    }

    public boolean exists(String code, String pos) { return glCodes.existsByCodeAndPosition(code, pos); }

    public List<GlCode> listWithUsage() {
        List<GlCode> rows = glCodes.findAllByOrderByCodeAscPositionAsc();
        for (GlCode g : rows) {
            long n = usageCount(g.getCode(), g.getPosition());
            g.setUsageCount(n);
            g.setUsagePending(n > 0 ? usagePending(g.getCode(), g.getPosition()) : 0);
            g.setUsed(n > 0);
        }
        return rows;
    }

    /** @return null on success, or an error key (exists/error). */
    public String add(String code, String pos, String desc, Short status, String by) {
        pos = normPos(pos);
        if (code == null || code.isBlank()) return "nocode";
        if (pos.isEmpty()) return "nopos";
        if (!validCode(code)) return "badcode";
        code = code.trim();
        if (glCodes.existsByCodeAndPosition(code, pos)) return "exists";
        GlCode g = new GlCode();
        g.setCode(code); g.setPosition(pos); g.setDescription(desc);
        g.setStatus(status == null ? 1 : status); g.setCreatedBy(by); g.setCreatedDate(LocalDateTime.now());
        glCodes.save(g);
        return null;
    }

    public String edit(Long id, String code, String pos, String desc, Short status, String by) {
        GlCode g = glCodes.findById(id).orElse(null);
        if (g == null) return "error";
        pos = normPos(pos);
        if (used(g)) {
            // locked: only description + status
            g.setDescription(desc); g.setStatus(status);
        } else {
            if (code == null || code.isBlank()) return "nocode";
            if (pos.isEmpty()) return "nopos";
            if (!validCode(code)) return "badcode";
            code = code.trim();
            glCodes.findByCodeAndPosition(code, pos)
                   .filter(other -> !other.getId().equals(id))
                   .ifPresent(o -> { throw new DuplicateGl(); });
            g.setCode(code); g.setPosition(pos); g.setDescription(desc); g.setStatus(status);
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

    /** Parse only — shared by the direct path and by the maker→checker request path. */
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
                String code = c.length > 0 ? c[0].trim() : "";
                String pos  = normPos(c.length > 1 ? c[1] : "");
                String desc = c.length > 2 ? c[2].trim() : "";
                if (code.isEmpty() || pos.isEmpty() || !validCode(code)) { bad++; continue; }
                if (glCodes.existsByCodeAndPosition(code, pos) || listed(rows, code, pos)) { dup++; continue; }
                Map<String,String> m = new LinkedHashMap<>();
                m.put("code", code); m.put("position", pos); m.put("description", desc);
                rows.add(m);
            }
        }
        return new CsvParse(rows, dup, bad);
    }

    private static boolean listed(List<Map<String,String>> rows, String code, String pos) {
        return rows.stream().anyMatch(m -> code.equals(m.get("code")) && pos.equals(m.get("position")));
    }

    /** Insert parsed rows, skipping any that became duplicates meanwhile. @return rows added. */
    public int applyImport(List<Map<String,String>> rows, String by) {
        int added = 0;
        if (rows == null) return 0;
        for (Map<String,String> m : rows) {
            String code = m.get("code"), pos = normPos(m.get("position"));
            if (!validCode(code) || pos.isEmpty()) continue;
            if (glCodes.existsByCodeAndPosition(code, pos)) continue;
            GlCode g = new GlCode();
            g.setCode(code); g.setPosition(pos); g.setDescription(m.get("description"));
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
