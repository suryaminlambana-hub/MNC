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
import java.util.List;

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

    public boolean used(String code) { return glTabel.existsByGltrac(code); }

    public List<GlCode> listWithUsage() {
        List<GlCode> rows = glCodes.findAllByOrderByCodeAscPositionAsc();
        for (GlCode g : rows) g.setUsed(used(g.getCode()));
        return rows;
    }

    /** @return null on success, or an error key (exists/error). */
    public String add(String code, String pos, String desc, Short status, String by) {
        pos = normPos(pos);
        if (code == null || code.isBlank() || pos.isEmpty()) return "error";
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
        if (used(g.getCode())) {
            // locked: only description + status
            g.setDescription(desc); g.setStatus(status);
        } else {
            if (code == null || code.isBlank() || pos.isEmpty()) return "error";
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
        if (used(g.getCode())) return "inuse";
        glCodes.delete(g);
        return null;
    }

    public int[] importCsv(InputStream in, String by) throws Exception {
        int added = 0, dup = 0, bad = 0, line = 0;
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
                if (code.isEmpty() || pos.isEmpty()) { bad++; continue; }
                if (glCodes.existsByCodeAndPosition(code, pos)) { dup++; continue; }
                GlCode g = new GlCode();
                g.setCode(code); g.setPosition(pos); g.setDescription(desc);
                g.setStatus((short)1); g.setCreatedBy(by); g.setCreatedDate(LocalDateTime.now());
                glCodes.save(g); added++;
            }
        }
        return new int[]{added, dup, bad};
    }

    public static class DuplicateGl extends RuntimeException {}
}
