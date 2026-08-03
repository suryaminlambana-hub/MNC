package com.mnc.ausy.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mnc.ausy.domain.GlChangeRequest;
import com.mnc.ausy.domain.GlTabel;
import com.mnc.ausy.repo.GlChangeRequestRepository;
import com.mnc.ausy.repo.GlTabelRepository;
import com.mnc.ausy.security.AusyUserDetails;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;

/**
 * Workflow 8.3 — Update GL (Add/Edit/Delete) under dual control.
 * Maker submits a change request (data unchanged until approval); Checker approves/rejects
 * after seeing before/after. Delete is a soft-delete (active=0). Every step is audited.
 */
@Service
public class GlChangeService {

    /** Editable fields, in display order: key -> label. */
    public static final LinkedHashMap<String,String> FIELDS = new LinkedHashMap<>();
    static {
        FIELDS.put("gltrac", "GL Code");
        FIELDS.put("gltrcd", "D/C");
        FIELDS.put("gltram", "Amount");
        FIELDS.put("gltrds", "Description");
        FIELDS.put("noPaket", "Paket");
    }

    private final GlTabelRepository gl;
    private final GlChangeRequestRepository cr;
    private final ApprovalService approval;
    private final AuditService audit;
    private final ObjectMapper json = new ObjectMapper();

    public GlChangeService(GlTabelRepository gl, GlChangeRequestRepository cr, ApprovalService approval, AuditService audit) {
        this.gl = gl; this.cr = cr; this.approval = approval; this.audit = audit;
    }

    // ---- maps & json helpers ----
    private Map<String,String> rowMap(GlTabel g) {
        Map<String,String> m = new LinkedHashMap<>();
        m.put("gltrac", n(g.getGltrac())); m.put("gltrcd", n(g.getGltrcd()));
        m.put("gltram", g.getGltram()==null?"":g.getGltram().toPlainString());
        m.put("gltrds", n(g.getGltrds())); m.put("noPaket", n(g.getNoPaket()));
        return m;
    }
    private Map<String,String> formMap(Map<String,String> f) {
        Map<String,String> m = new LinkedHashMap<>();
        for (String k : FIELDS.keySet()) m.put(k, n(f.get(k)));
        return m;
    }
    private String toJson(Map<String,String> m) {
        try { return json.writeValueAsString(m); } catch (Exception e) { return "{}"; }
    }
    @SuppressWarnings("unchecked")
    public Map<String,String> parse(String s) {
        if (s == null || s.isBlank()) return null;
        try { return json.readValue(s, LinkedHashMap.class); } catch (Exception e) { return null; }
    }
    private String toJsonList(List<Map<String,String>> l) {
        try { return json.writeValueAsString(l); } catch (Exception e) { return "[]"; }
    }
    @SuppressWarnings("unchecked")
    public List<Map<String,String>> parseList(String s) {
        if (s == null || s.isBlank()) return java.util.Collections.emptyList();
        try { return json.readValue(s, List.class); } catch (Exception e) { return java.util.Collections.emptyList(); }
    }
    private static String n(String s){ return s==null?"":s; }

    // ---- maker actions: create pending request, do NOT touch live data ----
    @Transactional
    public String submitAdd(Map<String,String> f, AusyUserDetails me, String note) {
        GlChangeRequest c = base("ADD", null, me, note);
        c.setNoPaket(n(f.get("noPaket")));
        c.setAfterJson(toJson(formMap(f)));
        cr.save(c);
        audit.transaction(me.getUsername(), "GL_CHANGE_REQ", "ADD diajukan (CR#" + c.getId() + ")");
        return "Permintaan TAMBAH data GL diajukan (menunggu approval Checker).";
    }

    @Transactional
    public String submitEdit(Long glId, Map<String,String> f, AusyUserDetails me, String note) {
        GlTabel g = gl.findById(glId).orElse(null);
        if (g == null) return "Baris GL tidak ditemukan.";
        GlChangeRequest c = base("EDIT", glId, me, note);
        c.setNoPaket(g.getNoPaket());
        c.setBeforeJson(toJson(rowMap(g)));
        c.setAfterJson(toJson(formMap(f)));
        cr.save(c);
        audit.transaction(me.getUsername(), "GL_CHANGE_REQ", "EDIT baris #" + glId + " diajukan (CR#" + c.getId() + ")");
        return "Permintaan UBAH data GL diajukan (menunggu approval Checker).";
    }

    /**
     * Bulk edit via Excel round-trip: maker exports GL, edits offline, re-uploads.
     * We match each uploaded row to a live GL row by ID, keep only the rows that actually
     * changed, and store the whole batch (before + after) in ONE change request so the
     * checker can export/compare the Excel before & after, then approve/reject.
     */
    @Transactional
    public String submitBulkEditExcel(List<Map<String,String>> uploaded, AusyUserDetails me, String note) {
        if (note == null || note.isBlank()) return "Alasan/justifikasi wajib diisi.";
        if (uploaded == null || uploaded.isEmpty()) return "File kosong / tidak ada baris data.";
        List<Map<String,String>> before = new ArrayList<>(), after = new ArrayList<>();
        int skipped = 0;
        for (Map<String,String> row : uploaded) {
            Long id;
            try { id = Long.valueOf(row.get("id")); } catch (Exception e) { skipped++; continue; }
            GlTabel g = gl.findById(id).orElse(null);
            if (g == null) { skipped++; continue; }
            Map<String,String> cur = rowMap(g);
            Map<String,String> neu = new LinkedHashMap<>(cur);
            for (String k : FIELDS.keySet()) if (row.get(k) != null) {
                neu.put(k, row.get(k).trim());
            }
            // normalise amount on both sides so "200000.00" vs "200000" isn't seen as a change
            cur.put("gltram", normAmt(cur.get("gltram")));
            neu.put("gltram", normAmt(neu.get("gltram")));
            if (!cur.equals(neu)) {
                Map<String,String> b = new LinkedHashMap<>(cur); b.put("id", String.valueOf(id));
                Map<String,String> a = new LinkedHashMap<>(neu); a.put("id", String.valueOf(id));
                before.add(b); after.add(a);
            }
        }
        if (after.isEmpty()) return "Tidak ada perubahan terdeteksi di file (dibandingkan data saat ini).";
        GlChangeRequest c = base("BULK_EDIT", null, me, note);
        c.setBeforeJson(toJsonList(before));
        c.setAfterJson(toJsonList(after));
        cr.save(c);
        audit.transaction(me.getUsername(), "GL_CHANGE_REQ",
                "BULK EDIT (Excel) " + after.size() + " baris diajukan (CR#" + c.getId() + ")");
        return "Bulk edit " + after.size() + " baris GL diajukan via Excel (menunggu approval Checker)"
                + (skipped > 0 ? " — " + skipped + " baris dilewati (ID tidak valid / tidak ditemukan)." : ".");
    }

    /** Rows (before/after) of a bulk-edit request, for the checker's Excel export. */
    public List<Map<String,String>> bulkRows(Long crId, boolean after) {
        GlChangeRequest c = cr.findById(crId).orElse(null);
        if (c == null) return java.util.Collections.emptyList();
        return parseList(after ? c.getAfterJson() : c.getBeforeJson());
    }

    @Transactional
    public String submitDelete(Long glId, AusyUserDetails me, String note) {
        GlTabel g = gl.findById(glId).orElse(null);
        if (g == null) return "Baris GL tidak ditemukan.";
        GlChangeRequest c = base("DELETE", glId, me, note);
        c.setNoPaket(g.getNoPaket());
        c.setBeforeJson(toJson(rowMap(g)));
        cr.save(c);
        audit.transaction(me.getUsername(), "GL_CHANGE_REQ", "DELETE baris #" + glId + " diajukan (CR#" + c.getId() + ")");
        return "Permintaan HAPUS data GL diajukan (menunggu approval Checker).";
    }

    private GlChangeRequest base(String op, Long glId, AusyUserDetails me, String note) {
        GlChangeRequest c = new GlChangeRequest();
        c.setOp(op); c.setGlId(glId); c.setMaker(me.getUsername());
        c.setMakerGroup(me.getIdGroup()); c.setNoteMaker(note); c.setStatus("PENDING");
        return c;
    }

    // ---- checker review ----
    public record Diff(String label, String before, String after, boolean changed) {}
    public record ReviewItem(GlChangeRequest cr, List<Diff> diffs, int bulkCount) {}

    public List<ReviewItem> pendingReviewFor(Integer approverGroup) {
        List<ReviewItem> out = new ArrayList<>();
        for (GlChangeRequest c : cr.findByStatusOrderByCreatedAtDesc("PENDING")) {
            if (!approval.canApprove(approverGroup, c.getMaker())) continue;
            if (isStale(c)) continue;
            if ("BULK_EDIT".equals(c.getOp()))
                out.add(new ReviewItem(c, List.of(), parseList(c.getAfterJson()).size()));
            else
                out.add(new ReviewItem(c, diffs(c), 0));
        }
        return out;
    }

    /** Pending changes whose target GL still exists & is active (drop stale ones — GL sudah tidak ada). */
    public List<GlChangeRequest> pendingVisible() {
        List<GlChangeRequest> out = new ArrayList<>();
        for (GlChangeRequest c : cr.findByStatusOrderByCreatedAtDesc("PENDING")) if (!isStale(c)) out.add(c);
        return out;
    }
    public long pendingVisibleCount() { return pendingVisible().size(); }

    /** A pending change is stale only when it targets an EXISTING GL row that no longer exists
     *  (EDIT/DELETE with a gl_id that's gone or soft-deleted). ADD & BULK_EDIT are always shown —
     *  they are pending decisions awaiting the checker, so they must never disappear on their own. */
    private boolean isStale(GlChangeRequest c) {
        if (!"EDIT".equals(c.getOp()) && !"DELETE".equals(c.getOp())) return false;
        Long id = c.getGlId();
        if (id == null) return false;
        return gl.findById(id).map(g -> g.getActive() != null && g.getActive() == 0).orElse(true);
    }

    private List<Diff> diffs(GlChangeRequest c) {
        Map<String,String> b = parse(c.getBeforeJson()), a = parse(c.getAfterJson());
        List<Diff> list = new ArrayList<>();
        for (var e : FIELDS.entrySet()) {
            String bv = b==null?null:b.get(e.getKey());
            String av = a==null?null:a.get(e.getKey());
            list.add(new Diff(e.getValue(), bv==null?"—":bv, av==null?"—":av, !Objects.equals(bv,av)));
        }
        return list;
    }

    @Transactional
    public String approve(Long crId, AusyUserDetails me, String note) {
        GlChangeRequest c = cr.findById(crId).orElse(null);
        if (c == null || !"PENDING".equals(c.getStatus())) return "Permintaan tidak ditemukan / sudah diproses.";
        if (!approval.canApprove(me.getIdGroup(), c.getMaker()))
            return "Group Anda tidak berwenang meng-approve permintaan dari maker tersebut.";

        switch (c.getOp()) {
            case "ADD" -> applyAdd(parse(c.getAfterJson()), c.getMaker());
            case "EDIT" -> applyEdit(c.getGlId(), parse(c.getAfterJson()));
            case "DELETE" -> applyDelete(c.getGlId());
            case "BULK_EDIT" -> applyBulkEdit(parseList(c.getAfterJson()));
            default -> { return "Operasi tidak dikenal."; }
        }
        c.setStatus("APPROVED"); c.setChecker(me.getUsername());
        c.setNoteChecker(note); c.setDecidedAt(LocalDateTime.now());
        cr.save(c);
        audit.transaction(me.getUsername(), "GL_CHANGE_APPROVE",
                c.getOp() + " CR#" + c.getId() + " (maker=" + c.getMaker() + ") di-approve & diterapkan ke DB");
        return c.getOp() + " disetujui dan diterapkan ke database GL.";
    }

    @Transactional
    public String reject(Long crId, AusyUserDetails me, String note) {
        GlChangeRequest c = cr.findById(crId).orElse(null);
        if (c == null || !"PENDING".equals(c.getStatus())) return "Permintaan tidak ditemukan / sudah diproses.";
        if (!approval.canApprove(me.getIdGroup(), c.getMaker()))
            return "Group Anda tidak berwenang menolak permintaan dari maker tersebut.";
        if (note == null || note.isBlank()) return "Catatan penolakan wajib diisi.";
        // rejected: live GL data is left unchanged (8.3.d)
        c.setStatus("REJECTED"); c.setChecker(me.getUsername());
        c.setNoteChecker(note); c.setDecidedAt(LocalDateTime.now());
        cr.save(c);
        audit.transaction(me.getUsername(), "GL_CHANGE_REJECT",
                c.getOp() + " CR#" + c.getId() + " (maker=" + c.getMaker() + ") DITOLAK. Catatan: " + note);
        return "Permintaan ditolak. Data GL tidak diubah; alasan tercatat di audit trail.";
    }

    // ---- apply to live data ----
    private void applyAdd(Map<String,String> a, String maker) {
        if (a == null) return;
        GlTabel g = new GlTabel();
        g.setGltrac(a.get("gltrac")); g.setGltrcd(a.get("gltrcd"));
        g.setGltram(amt(a.get("gltram"))); g.setGltrds(a.get("gltrds"));
        g.setNoPaket(a.get("noPaket"));
        g.setGltrus(maker.length()>10?maker.substring(0,10):maker);
        g.setStatusPrint("BELUM UPLOAD"); g.setStatusDual("APPROVED");
        g.setGlsrc("m"); g.setActive((short)1);
        gl.save(g);
    }
    private void applyEdit(Long glId, Map<String,String> a) {
        if (glId == null || a == null) return;
        gl.findById(glId).ifPresent(g -> {
            g.setGltrac(a.get("gltrac")); g.setGltrcd(a.get("gltrcd"));
            g.setGltram(amt(a.get("gltram"))); g.setGltrds(a.get("gltrds"));
            g.setNoPaket(a.get("noPaket"));
            gl.save(g);
        });
    }
    private void applyDelete(Long glId) {
        if (glId == null) return;
        gl.findById(glId).ifPresent(g -> { g.setActive((short)0); gl.save(g); });  // soft delete
    }
    private void applyBulkEdit(List<Map<String,String>> rows) {
        for (Map<String,String> a : rows) {
            Long id;
            try { id = Long.valueOf(a.get("id")); } catch (Exception e) { continue; }
            gl.findById(id).ifPresent(g -> {
                g.setGltrac(a.get("gltrac")); g.setGltrcd(a.get("gltrcd"));
                g.setGltram(amt(a.get("gltram"))); g.setGltrds(a.get("gltrds"));
                g.setNoPaket(a.get("noPaket"));
                gl.save(g);
            });
        }
    }

    private static BigDecimal amt(String s) {
        if (s == null || s.isBlank()) return BigDecimal.ZERO;
        try { return new BigDecimal(s.replace(",", "").trim()); } catch (Exception e) { return BigDecimal.ZERO; }
    }
    /** Canonical numeric form so different scales ("200000.00" vs "200000") compare equal. */
    private static String normAmt(String s) {
        return amt(s).stripTrailingZeros().toPlainString();
    }
}
