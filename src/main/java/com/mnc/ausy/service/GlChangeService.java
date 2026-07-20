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
    public record ReviewItem(GlChangeRequest cr, List<Diff> diffs) {}

    public List<ReviewItem> pendingReviewFor(Integer approverGroup) {
        List<ReviewItem> out = new ArrayList<>();
        for (GlChangeRequest c : cr.findByStatusOrderByCreatedAtDesc("PENDING")) {
            if (!approval.canApprove(approverGroup, c.getMaker())) continue;
            out.add(new ReviewItem(c, diffs(c)));
        }
        return out;
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

    private static BigDecimal amt(String s) {
        if (s == null || s.isBlank()) return BigDecimal.ZERO;
        try { return new BigDecimal(s.replace(",", "").trim()); } catch (Exception e) { return BigDecimal.ZERO; }
    }
}
