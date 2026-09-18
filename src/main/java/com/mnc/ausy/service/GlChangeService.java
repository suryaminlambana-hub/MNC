package com.mnc.ausy.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mnc.ausy.domain.GlChangeRequest;
import com.mnc.ausy.domain.GlCode;
import com.mnc.ausy.domain.GlExportRequest;
import com.mnc.ausy.domain.GlTabel;
import com.mnc.ausy.repo.GlChangeRequestRepository;
import com.mnc.ausy.repo.GlExportRequestRepository;
import com.mnc.ausy.repo.GlTabelRepository;
import com.mnc.ausy.security.AusyUserDetails;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * Workflow 8.3 — every maker-initiated change under dual control. The maker submits a request
 * (live data untouched); the Checker approves/rejects after seeing before/after. Three targets:
 *
 * <ul>
 *   <li>{@code GL_ROW}  — Add/Edit/Delete/Bulk-edit a GL row (Kelola Data GL). Delete = soft-delete.</li>
 *   <li>{@code GL_CODE} — Add/Edit/Delete/Import on the GL Code master (Manage GL Code).</li>
 *   <li>{@code PAKET}   — delete a whole batch (Hapus Batch).</li>
 * </ul>
 *
 * Every step is written to the audit trail.
 */
@Service
public class GlChangeService {

    public static final String T_GL_ROW = "GL_ROW", T_GL_CODE = "GL_CODE", T_PAKET = "PAKET";

    /** Editable fields, in display order: key -> label. */
    public static final LinkedHashMap<String,String> FIELDS = new LinkedHashMap<>();
    static {
        FIELDS.put("gltrac", "GL Code");
        FIELDS.put("gltrcd", "D/C");
        FIELDS.put("gltram", "Amount");
        FIELDS.put("gltrds", "Description");
        FIELDS.put("noPaket", "Batch");
    }

    /** GL Code master fields. */
    public static final LinkedHashMap<String,String> GLCODE_FIELDS = new LinkedHashMap<>();
    static {
        GLCODE_FIELDS.put("code", "NoGL");
        GLCODE_FIELDS.put("position", "Debit/Credit");
        GLCODE_FIELDS.put("description", "Description");
        GLCODE_FIELDS.put("status", "Status");
    }

    /** Batch summary fields (delete request). */
    public static final LinkedHashMap<String,String> PAKET_FIELDS = new LinkedHashMap<>();
    static {
        PAKET_FIELDS.put("noPaket", "Batch");
        PAKET_FIELDS.put("inputer", "Uploader");
        PAKET_FIELDS.put("rows", "Jumlah Baris");
        PAKET_FIELDS.put("debit", "Total Debit");
        PAKET_FIELDS.put("credit", "Total Credit");
    }

    private static LinkedHashMap<String,String> fieldsOf(String target) {
        if (T_GL_CODE.equals(target)) return GLCODE_FIELDS;
        if (T_PAKET.equals(target))   return PAKET_FIELDS;
        return FIELDS;
    }

    public static String targetLabel(String target) {
        if (T_GL_CODE.equals(target)) return "Kode GL";
        if (T_PAKET.equals(target))   return "Batch";
        return "Data GL";
    }

    /** Group pengaju: dari permintaannya sendiri, baru dari record user kalau belum tersimpan. */
    private Integer makerGroupOf(GlChangeRequest c) {
        return c.getMakerGroup() != null ? c.getMakerGroup() : approval.groupOf(c.getMaker());
    }

    /** Outcome of a maker/checker action: accepted or not, plus the message to flash. */
    public record Res(boolean ok, String message) {}

    private final GlTabelRepository gl;
    private final GlChangeRequestRepository cr;
    private final ApprovalService approval;
    private final AuditService audit;
    private final GlCodeService glCodeService;
    private final GlExportRequestRepository exportRequests;
    private final SequenceService seq;
    private final DataScopeService scope;
    private final ObjectMapper json = new ObjectMapper();

    /** Posting date format the core expects (same as the Excel upload path). */
    private static final DateTimeFormatter DDMMYY = DateTimeFormatter.ofPattern("ddMMyy");

    public GlChangeService(GlTabelRepository gl, GlChangeRequestRepository cr, ApprovalService approval,
                           AuditService audit, GlCodeService glCodeService,
                           GlExportRequestRepository exportRequests, SequenceService seq,
                           DataScopeService scope) {
        this.gl = gl; this.cr = cr; this.approval = approval; this.audit = audit; this.glCodeService = glCodeService;
        this.exportRequests = exportRequests; this.seq = seq; this.scope = scope;
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
    private static boolean blank(String s){ return s == null || s.isBlank(); }

    // ---- maker actions: create pending request, do NOT touch live data ----
    @Transactional
    public Res submitAdd(Map<String,String> f, AusyUserDetails me, String note) {
        if (blank(note)) return new Res(false, "Alasan/justifikasi wajib diisi.");
        if (!approval.hasActiveApprover(me.getIdGroup())) return new Res(false, ApprovalService.NO_APPROVER);
        String lock = batchLockReason(n(f.get("noPaket")));      // BUG_AUSY_00106
        if (lock != null) return new Res(false, lock);
        Res badCode = checkGlCodeInput(f.get("gltrac"));         // BUG_AUSY_00104
        if (badCode != null) return badCode;
        GlChangeRequest c = base("ADD", T_GL_ROW, null, me, note);
        c.setNoPaket(n(f.get("noPaket")));
        c.setAfterJson(toJson(formMap(f)));
        cr.save(c);
        audit.transaction(me.getUsername(), "GL_CHANGE_REQ", "ADD diajukan (CR#" + c.getId() + ")");
        return new Res(true, "Permintaan TAMBAH data GL diajukan (menunggu approval Checker).");
    }

    @Transactional
    public Res submitEdit(Long glId, Map<String,String> f, AusyUserDetails me, String note) {
        if (blank(note)) return new Res(false, "Alasan/justifikasi wajib diisi.");
        if (!approval.hasActiveApprover(me.getIdGroup())) return new Res(false, ApprovalService.NO_APPROVER);
        GlTabel g = gl.findById(glId).orElse(null);
        if (g == null) return new Res(false, "Baris GL tidak ditemukan.");
        if (!scope.canSee(me, g.getGltrus())) return new Res(false, DataScopeService.NOT_MY_MAKER);   // BUG_AUSY_00138
        if (pendingGlRowIds().contains(glId))
            return new Res(false, lockReason(g));

        String newCode = n(f.get("gltrac")).trim();
        if (!newCode.equals(n(g.getGltrac()).trim())) {          // BUG_AUSY_00104
            Res badCode = checkGlCodeInput(newCode);
            if (badCode != null) return badCode;
        }
        Map<String,String> before = normRow(rowMap(g)), after = normRow(formMap(f));
        if (before.equals(after))
            return new Res(false, "Tidak ada perubahan pada data GL — permintaan tidak diajukan.");

        GlChangeRequest c = base("EDIT", T_GL_ROW, glId, me, note);
        c.setNoPaket(g.getNoPaket());
        c.setBeforeJson(toJson(before));
        c.setAfterJson(toJson(after));
        cr.save(c);
        audit.transaction(me.getUsername(), "GL_CHANGE_REQ", "EDIT baris #" + glId + " diajukan (CR#" + c.getId() + ")");
        return new Res(true, "Permintaan UBAH data GL diajukan (menunggu approval Checker).");
    }

    /**
     * GL Code on a GL row follows the same rule as NoGL on the master: digits only — BUG_AUSY_00104.
     * On edit this is checked only when the value actually changes, so a legacy row that arrived from
     * an upload with a non-numeric code can still have its other fields corrected.
     * @return the failure, or null when the input is acceptable.
     */
    private static Res checkGlCodeInput(String code) {
        if (blank(code)) return new Res(false, "GL Code wajib diisi.");
        if (!GlCodeService.validCode(code))
            return new Res(false, "GL Code hanya boleh berisi angka (tanpa huruf, spasi, atau tanda baca).");
        return null;
    }

    /**
     * Bulk edit via Excel round-trip: maker exports GL, edits offline, re-uploads.
     * We match each uploaded row to a live GL row by ID, keep only the rows that actually
     * changed, and store the whole batch (before + after) in ONE change request so the
     * checker can export/compare the Excel before & after, then approve/reject.
     */
    @Transactional
    public Res submitBulkEditExcel(List<Map<String,String>> uploaded, AusyUserDetails me, String note) {
        if (blank(note)) return new Res(false, "Alasan/justifikasi wajib diisi.");
        if (!approval.hasActiveApprover(me.getIdGroup())) return new Res(false, ApprovalService.NO_APPROVER);
        if (uploaded == null || uploaded.isEmpty()) return new Res(false, "File kosong / tidak ada baris data.");
        Set<Long> pending = pendingGlRowIds();
        List<Map<String,String>> before = new ArrayList<>(), after = new ArrayList<>();
        int skipped = 0, held = 0, badCode = 0;
        for (Map<String,String> row : uploaded) {
            Long id;
            try { id = Long.valueOf(row.get("id")); } catch (Exception e) { skipped++; continue; }
            if (pending.contains(id)) { held++; continue; }     // sedang menunggu otorisasi
            GlTabel g = gl.findById(id).orElse(null);
            if (g == null) { skipped++; continue; }
            // Baris milik maker lain diabaikan, walau id-nya diketik manual di Excel (BUG_AUSY_00138)
            if (!scope.canSee(me, g.getGltrus())) { skipped++; continue; }
            Map<String,String> cur = rowMap(g);
            Map<String,String> neu = new LinkedHashMap<>(cur);
            for (String k : FIELDS.keySet()) if (row.get(k) != null) {
                neu.put(k, row.get(k).trim());
            }
            // BUG_AUSY_00104: GL Code hasil edit di Excel juga harus angka
            String code = n(neu.get("gltrac")).trim();
            if (!code.equals(n(cur.get("gltrac")).trim()) && !GlCodeService.validCode(code)) { badCode++; continue; }
            // normalise amount on both sides so "200000.00" vs "200000" isn't seen as a change
            cur.put("gltram", normAmt(cur.get("gltram")));
            neu.put("gltram", normAmt(neu.get("gltram")));
            if (!cur.equals(neu)) {
                Map<String,String> b = new LinkedHashMap<>(cur); b.put("id", String.valueOf(id));
                Map<String,String> a = new LinkedHashMap<>(neu); a.put("id", String.valueOf(id));
                before.add(b); after.add(a);
            }
        }
        if (after.isEmpty()) return new Res(false, "Tidak ada perubahan terdeteksi di file (dibandingkan data saat ini)."
                + (held > 0 ? " " + held + " baris dilewati karena sedang menunggu otorisasi." : "")
                + (badCode > 0 ? " " + badCode + " baris dilewati karena GL Code bukan angka." : ""));
        GlChangeRequest c = base("BULK_EDIT", T_GL_ROW, null, me, note);
        c.setBeforeJson(toJsonList(before));
        c.setAfterJson(toJsonList(after));
        cr.save(c);
        audit.transaction(me.getUsername(), "GL_CHANGE_REQ",
                "BULK EDIT (Excel) " + after.size() + " baris diajukan (CR#" + c.getId() + ")");
        return new Res(true, "Bulk edit " + after.size() + " baris GL diajukan via Excel (menunggu approval Checker)"
                + (skipped > 0 ? " — " + skipped + " baris dilewati (ID tidak valid / tidak ditemukan)." : ".")
                + (held > 0 ? " " + held + " baris dilewati karena sedang menunggu otorisasi." : "")
                + (badCode > 0 ? " " + badCode + " baris dilewati karena GL Code bukan angka." : ""));
    }

    /** Rows (before/after) of a bulk-edit request, for the checker's Excel export. */
    public List<Map<String,String>> bulkRows(Long crId, boolean after) {
        GlChangeRequest c = cr.findById(crId).orElse(null);
        if (c == null) return java.util.Collections.emptyList();
        return parseList(after ? c.getAfterJson() : c.getBeforeJson());
    }

    @Transactional
    public Res submitDelete(Long glId, AusyUserDetails me, String note) {
        if (blank(note)) return new Res(false, "Alasan/justifikasi wajib diisi.");
        if (!approval.hasActiveApprover(me.getIdGroup())) return new Res(false, ApprovalService.NO_APPROVER);
        GlTabel g = gl.findById(glId).orElse(null);
        if (g == null) return new Res(false, "Baris GL tidak ditemukan.");
        if (!scope.canSee(me, g.getGltrus())) return new Res(false, DataScopeService.NOT_MY_MAKER);   // BUG_AUSY_00138
        if (pendingGlRowIds().contains(glId))
            return new Res(false, lockReason(g));
        GlChangeRequest c = base("DELETE", T_GL_ROW, glId, me, note);
        c.setNoPaket(g.getNoPaket());
        c.setBeforeJson(toJson(rowMap(g)));
        cr.save(c);
        audit.transaction(me.getUsername(), "GL_CHANGE_REQ", "DELETE baris #" + glId + " diajukan (CR#" + c.getId() + ")");
        return new Res(true, "Permintaan HAPUS data GL diajukan (menunggu approval Checker).");
    }

    // ---- maker actions on the GL Code master (BUG_AUSY_00080) ----
    @Transactional
    public Res submitGlCodeAdd(String code, String pos, String desc, Short status, AusyUserDetails me, String note) {
        if (blank(note)) return new Res(false, "Alasan/justifikasi wajib diisi.");
        if (!approval.hasActiveApprover(me.getIdGroup())) return new Res(false, ApprovalService.NO_APPROVER);
        String p = GlCodeService.normPos(pos);
        Res bad = checkCode(code, p);
        if (bad != null) return bad;
        code = code.trim();
        if (glCodeService.exists(code, p)) return new Res(false, "Kombinasi NoGL + Debit/Credit sudah terdaftar.");
        if (pendingCombo(code, p)) return new Res(false, "Sudah ada permintaan untuk NoGL tersebut yang menunggu otorisasi Checker.");
        GlChangeRequest c = base("ADD", T_GL_CODE, null, me, note);
        c.setAfterJson(toJson(glCodeMap(code, p, desc, status)));
        cr.save(c);
        audit.transaction(me.getUsername(), "GLCODE_CHANGE_REQ",
                "TAMBAH Kode GL " + code + "-" + p + " diajukan (CR#" + c.getId() + ")");
        return new Res(true, "Permintaan TAMBAH Kode GL diajukan (menunggu otorisasi Checker).");
    }

    @Transactional
    public Res submitGlCodeEdit(Long id, String code, String pos, String desc, Short status, AusyUserDetails me, String note) {
        if (blank(note)) return new Res(false, "Alasan/justifikasi wajib diisi.");
        if (!approval.hasActiveApprover(me.getIdGroup())) return new Res(false, ApprovalService.NO_APPROVER);
        GlCode g = glCodeService.find(id);
        if (g == null) return new Res(false, "Kode GL tidak ditemukan.");
        if (pendingForGlCode(id)) return new Res(false, "Kode GL tersebut masih menunggu otorisasi Checker.");

        String newCode, newPos;
        if (glCodeService.used(g)) {                    // used codes: NoGL + D/C stay locked
            newCode = g.getCode(); newPos = g.getPosition();
        } else {
            newPos = GlCodeService.normPos(pos);
            newCode = code == null ? "" : code.trim();
            Res bad = checkCode(newCode, newPos);
            if (bad != null) return bad;
            boolean moved = !newCode.equals(g.getCode()) || !newPos.equals(g.getPosition());
            if (moved && glCodeService.exists(newCode, newPos))
                return new Res(false, "Kombinasi NoGL + Debit/Credit sudah terdaftar.");
        }
        Map<String,String> before = glCodeMap(g), after = glCodeMap(newCode, newPos, desc, status);
        if (before.equals(after))
            return new Res(false, "Tidak ada perubahan pada Kode GL — permintaan tidak diajukan.");

        GlChangeRequest c = base("EDIT", T_GL_CODE, null, me, note);
        c.setRefId(id);
        c.setBeforeJson(toJson(before));
        c.setAfterJson(toJson(after));
        cr.save(c);
        audit.transaction(me.getUsername(), "GLCODE_CHANGE_REQ", "UBAH Kode GL #" + id + " diajukan (CR#" + c.getId() + ")");
        return new Res(true, "Permintaan UBAH Kode GL diajukan (menunggu otorisasi Checker).");
    }

    /**
     * NoGL / D-C validation shared by the add + edit request paths: an empty NoGL is rejected with a
     * message that names the field (BUG_AUSY_00105) and a NoGL with anything but digits is rejected
     * outright (BUG_AUSY_00104). @return the failure, or null when the input is acceptable.
     */
    private static Res checkCode(String code, String normPos) {
        if (blank(code))                       return new Res(false, "NoGL wajib diisi.");
        if (normPos == null || normPos.isEmpty()) return new Res(false, "Debit/Credit wajib dipilih.");
        if (!GlCodeService.validCode(code))
            return new Res(false, "NoGL hanya boleh berisi angka (tanpa huruf, spasi, atau tanda baca).");
        return null;
    }

    @Transactional
    public Res submitGlCodeDelete(Long id, AusyUserDetails me, String note) {
        if (blank(note)) return new Res(false, "Alasan/justifikasi wajib diisi.");
        if (!approval.hasActiveApprover(me.getIdGroup())) return new Res(false, ApprovalService.NO_APPROVER);
        GlCode g = glCodeService.find(id);
        if (g == null) return new Res(false, "Kode GL tidak ditemukan.");
        if (glCodeService.used(g))
            return new Res(false, "Kode GL sudah dipakai pada transaksi, tidak dapat dihapus.");
        if (pendingForGlCode(id)) return new Res(false, "Kode GL tersebut masih menunggu otorisasi Checker.");
        GlChangeRequest c = base("DELETE", T_GL_CODE, null, me, note);
        c.setRefId(id);
        c.setBeforeJson(toJson(glCodeMap(g)));
        cr.save(c);
        audit.transaction(me.getUsername(), "GLCODE_CHANGE_REQ",
                "HAPUS Kode GL #" + id + " (" + g.getCode() + "-" + g.getPosition() + ") diajukan (CR#" + c.getId() + ")");
        return new Res(true, "Permintaan HAPUS Kode GL diajukan (menunggu otorisasi Checker).");
    }

    /** Bulk CSV import is an Add too — one request holding every new row. */
    @Transactional
    public Res submitGlCodeImport(List<Map<String,String>> rows, AusyUserDetails me, String note) {
        if (blank(note)) return new Res(false, "Alasan/justifikasi wajib diisi.");
        if (!approval.hasActiveApprover(me.getIdGroup())) return new Res(false, ApprovalService.NO_APPROVER);
        if (rows == null || rows.isEmpty()) return new Res(false, "Tidak ada baris baru yang bisa diajukan dari CSV.");
        List<Map<String,String>> fresh = new ArrayList<>();
        int held = 0;
        for (Map<String,String> m : rows) {
            if (pendingCombo(m.get("code"), m.get("position"))) { held++; continue; }
            fresh.add(m);
        }
        if (fresh.isEmpty()) return new Res(false, "Semua baris CSV sudah ada pada permintaan yang menunggu otorisasi.");
        GlChangeRequest c = base("IMPORT", T_GL_CODE, null, me, note);
        c.setAfterJson(toJsonList(fresh));
        cr.save(c);
        audit.transaction(me.getUsername(), "GLCODE_CHANGE_REQ",
                "IMPORT " + fresh.size() + " Kode GL diajukan (CR#" + c.getId() + ")");
        return new Res(true, "Permintaan IMPORT " + fresh.size() + " Kode GL diajukan (menunggu otorisasi Checker)."
                + (held > 0 ? " " + held + " baris dilewati karena sudah menunggu otorisasi." : ""));
    }

    // ---- maker action on a whole batch (BUG_AUSY_00082) ----
    @Transactional
    public Res submitPaketDelete(String paket, String inputer, AusyUserDetails me, String note) {
        if (blank(note)) return new Res(false, "Alasan/justifikasi wajib diisi.");
        if (!approval.hasActiveApprover(me.getIdGroup())) return new Res(false, ApprovalService.NO_APPROVER);
        List<GlTabel> rows = gl.findBatchRows(paket, inputer);
        if (rows.isEmpty()) return new Res(false, "Batch tidak ditemukan.");
        if (cr.existsByStatusAndTargetAndNoPaketAndInputer("PENDING", T_PAKET, paket, inputer))
            return new Res(false, "Batch tersebut sudah diajukan untuk dihapus dan menunggu otorisasi Checker.");
        // BUG_AUSY_00106: ACCTXT-nya sudah masuk antrean checker — batch tidak boleh hilang di tengah jalan
        if (exportPendingPakets().contains(n(paket).trim()))
            return new Res(false, "Batch " + paket + " sedang diajukan untuk export dan menunggu otorisasi Checker"
                    + " — batch tidak dapat dihapus sampai otorisasi export diputuskan.");
        List<Long> pendingRows = pendingRowIdsInPaket(paket, inputer);
        if (!pendingRows.isEmpty())
            return new Res(false, "Batch " + paket + " tidak dapat dihapus: masih ada data yang menunggu otorisasi (ID "
                    + idList(pendingRows) + "). Selesaikan otorisasi data tersebut lebih dulu.");

        BigDecimal d = BigDecimal.ZERO, k = BigDecimal.ZERO;
        for (GlTabel g : rows) {
            BigDecimal a = g.getGltram() == null ? BigDecimal.ZERO : g.getGltram();
            if ("C".equalsIgnoreCase(n(g.getGltrcd()))) k = k.add(a); else d = d.add(a);
        }
        Map<String,String> before = new LinkedHashMap<>();
        before.put("noPaket", paket); before.put("inputer", inputer);
        before.put("rows", String.valueOf(rows.size()));
        before.put("debit", d.toPlainString()); before.put("credit", k.toPlainString());

        GlChangeRequest c = base("DELETE", T_PAKET, null, me, note);
        c.setNoPaket(paket); c.setInputer(inputer);
        c.setBeforeJson(toJson(before));
        cr.save(c);
        audit.transaction(me.getUsername(), "BATCH_DELETE_REQ",
                "HAPUS Batch " + paket + " (uploader=" + inputer + ", " + rows.size() + " baris) diajukan (CR#" + c.getId() + ")");
        return new Res(true, "Permintaan HAPUS Batch " + paket + " diajukan (menunggu otorisasi Checker).");
    }

    // ---- pending lookups: the maker screens lock what is already submitted ----
    public Set<Long> pendingGlCodeIds() {
        Set<Long> ids = new HashSet<>();
        for (GlChangeRequest c : cr.findByStatusAndTarget("PENDING", T_GL_CODE))
            if (c.getRefId() != null) ids.add(c.getRefId());
        return ids;
    }

    /**
     * GL rows the maker must leave alone until a checker decides: EDIT/DELETE targets, every row
     * inside a pending bulk edit, and — BUG_AUSY_00099 — every row of a batch that is itself
     * awaiting a delete authorisation.
     */
    public Set<Long> pendingGlRowIds() {
        return pendingGlRowLabels().keySet();
    }

    /**
     * Same rows as {@link #pendingGlRowIds()}, each mapped to the reason it is locked, so the maker
     * screens can say WHY otorisasi is pending instead of a bare "Menunggu Otorisasi"
     * (BUG_AUSY_00099 follow-up). A pending batch deletion wins over a row-level request: it is the
     * broader hold, and the two cannot coexist anyway (BUG_AUSY_00096 blocks the other order).
     */
    public Map<Long,String> pendingGlRowLabels() {
        Map<Long,String> out = new LinkedHashMap<>();
        for (GlChangeRequest c : cr.findByStatusAndTarget("PENDING", T_GL_ROW)) {
            String label = switch (n(c.getOp())) {
                case "EDIT"      -> "Menunggu Otorisasi Ubah Data";
                case "DELETE"    -> "Menunggu Otorisasi Hapus Data";
                case "BULK_EDIT" -> "Menunggu Otorisasi Bulk Edit";
                default          -> "Menunggu Otorisasi";
            };
            if (c.getGlId() != null) out.put(c.getGlId(), label);
            if ("BULK_EDIT".equals(c.getOp()))
                for (Map<String,String> m : parseList(c.getAfterJson()))
                    try { out.put(Long.valueOf(m.get("id")), label); } catch (Exception ignored) {}
        }
        for (GlChangeRequest c : cr.findByStatusAndTarget("PENDING", T_PAKET))
            for (GlTabel g : gl.findBatchRows(c.getNoPaket(), c.getInputer()))
                out.put(g.getId(), "Menunggu Otorisasi Hapus Batch " + n(c.getNoPaket()));
        // BUG_AUSY_00106 — baris yang export-nya sudah diajukan ikut terkunci sampai checker memutuskan
        for (GlExportRequest r : exportRequests.findByStatusOrderByCreatedAtDesc("PENDING"))
            for (GlTabel g : gl.findByExportReqIdOrderById(r.getId()))
                out.put(g.getId(), "Menunggu Otorisasi Export Batch " + n(g.getNoPaket()));
        return out;
    }

    /**
     * Batches whose export request the Checker has not decided yet. The ACCTXT for those rows is
     * already queued, so nothing inside them may move until the export is approved or rejected
     * (BUG_AUSY_00106).
     */
    public Set<String> exportPendingPakets() {
        Set<String> out = new LinkedHashSet<>();
        for (GlExportRequest r : exportRequests.findByStatusOrderByCreatedAtDesc("PENDING"))
            for (GlTabel g : gl.findByExportReqIdOrderById(r.getId()))
                out.add(n(g.getNoPaket()).trim());
        return out;
    }

    /**
     * Why a whole batch is on hold, or null when it is free. Used where there is no GL row to look
     * at yet — e.g. the maker types a batch number on "Tambah Data GL".
     */
    public String batchLockReason(String paket) {
        String p = n(paket).trim();
        if (p.isEmpty()) return null;
        if (exportPendingPakets().contains(p)) return exportLockMsg(p);
        for (GlChangeRequest c : cr.findByStatusAndTarget("PENDING", T_PAKET))
            if (p.equals(n(c.getNoPaket()).trim())) return deleteLockMsg(p);
        return null;
    }

    private static String exportLockMsg(String paket) {
        return "Batch " + paket + " sedang diajukan untuk export — data di dalamnya tidak dapat diubah"
                + " sampai Checker memutuskan otorisasi export.";
    }

    private static String deleteLockMsg(String paket) {
        return "Batch " + paket + " sedang diajukan untuk dihapus — data di dalamnya tidak dapat diubah"
                + " sampai Checker memutuskan.";
    }

    /** Why a row is locked: its own request, a pending batch deletion, or a pending batch export. */
    private String lockReason(GlTabel g) {
        if (g != null) {
            String p = n(g.getNoPaket()).trim();
            if (exportPendingPakets().contains(p)) return exportLockMsg(p);
            if (cr.existsByStatusAndTargetAndNoPaketAndInputer("PENDING", T_PAKET, g.getNoPaket(), g.getGltrus()))
                return deleteLockMsg(p);
        }
        return "Baris GL tersebut masih menunggu otorisasi Checker.";
    }

    /** Canonical form of a GL row map, so "250000.00" vs " 250000 " isn't read as a change. */
    private static Map<String,String> normRow(Map<String,String> m) {
        Map<String,String> out = new LinkedHashMap<>();
        for (var e : m.entrySet()) {
            String v = n(e.getValue()).trim();
            out.put(e.getKey(), e.getKey().equals("gltram") ? normAmt(v) : v);
        }
        return out;
    }

    /**
     * GL rows inside one batch that still have a request waiting for the Checker.
     * A batch holding such rows must not be deleted (BUG_AUSY_00096).
     */
    public List<Long> pendingRowIdsInPaket(String paket, String inputer) {
        Set<Long> pending = pendingGlRowIds();
        List<Long> out = new ArrayList<>();
        if (pending.isEmpty()) return out;
        for (GlTabel g : gl.findBatchRows(paket, inputer))
            if (pending.contains(g.getId())) out.add(g.getId());
        return out;
    }

    /**
     * Batch numbers that still hold GL rows waiting on a checker — such a batch must not be
     * submitted for export until the pending change is decided (BUG_AUSY_00102).
     */
    public Set<String> pendingRowPakets() {
        Set<String> out = new LinkedHashSet<>();
        for (Long id : pendingGlRowIds())
            gl.findById(id).ifPresent(g -> out.add(n(g.getNoPaket()).trim()));
        return out;
    }

    /** Same thing for the whole Hapus Batch page: "paket|inputer" -> pending row ids. */
    public Map<String,List<Long>> pendingRowsByPaket() {
        Map<String,List<Long>> out = new LinkedHashMap<>();
        for (Long id : pendingGlRowIds())
            gl.findById(id).ifPresent(g ->
                    out.computeIfAbsent(n(g.getNoPaket()) + "|" + n(g.getGltrus()), k -> new ArrayList<>()).add(id));
        for (List<Long> ids : out.values()) Collections.sort(ids);
        return out;
    }

    private static String idList(List<Long> ids) {
        StringBuilder sb = new StringBuilder();
        for (Long id : ids) sb.append(sb.length() == 0 ? "" : ", ").append(id);
        return sb.toString();
    }

    public Set<String> pendingPaketKeys() {
        Set<String> keys = new HashSet<>();
        for (GlChangeRequest c : cr.findByStatusAndTarget("PENDING", T_PAKET))
            keys.add(n(c.getNoPaket()) + "|" + n(c.getInputer()));
        return keys;
    }

    private boolean pendingForGlCode(Long glCodeId) {
        return cr.existsByStatusAndTargetAndRefId("PENDING", T_GL_CODE, glCodeId);
    }

    /** Is an ADD/IMPORT for this NoGL + D/C already waiting? */
    private boolean pendingCombo(String code, String pos) {
        if (code == null || pos == null) return false;
        for (GlChangeRequest c : cr.findByStatusAndTarget("PENDING", T_GL_CODE)) {
            if ("IMPORT".equals(c.getOp())) {
                for (Map<String,String> m : parseList(c.getAfterJson()))
                    if (code.equals(m.get("code")) && pos.equals(m.get("position"))) return true;
            } else {
                Map<String,String> a = parse(c.getAfterJson());
                if (a != null && code.equals(a.get("code")) && pos.equals(a.get("position"))) return true;
            }
        }
        return false;
    }

    private Map<String,String> glCodeMap(String code, String pos, String desc, Short status) {
        Map<String,String> m = new LinkedHashMap<>();
        m.put("code", n(code)); m.put("position", n(pos)); m.put("description", n(desc));
        m.put("status", status == null ? "1" : String.valueOf(status));
        return m;
    }
    private Map<String,String> glCodeMap(GlCode g) {
        return glCodeMap(g.getCode(), g.getPosition(), g.getDescription(), g.getStatus());
    }

    private GlChangeRequest base(String op, String target, Long glId, AusyUserDetails me, String note) {
        GlChangeRequest c = new GlChangeRequest();
        c.setOp(op); c.setTarget(target); c.setGlId(glId); c.setMaker(me.getUsername());
        c.setMakerGroup(me.getIdGroup()); c.setNoteMaker(note); c.setStatus("PENDING");
        return c;
    }

    // ---- checker review ----
    public record Diff(String label, String before, String after, boolean changed) {}
    public record ReviewItem(GlChangeRequest cr, List<Diff> diffs, int bulkCount,
                             List<Map<String,String>> rows, String targetLabel) {}

    /**
     * Antrian otorisasi seorang checker: role-nya berwenang (matriks approval_group) DAN maker-nya
     * dipasangkan padanya di menu Pasangan Checker - Maker (BUG_AUSY_00139).
     */
    public List<ReviewItem> pendingReviewFor(AusyUserDetails me) {
        List<ReviewItem> out = new ArrayList<>();
        for (GlChangeRequest c : cr.findByStatusOrderByCreatedAtDesc("PENDING")) {
            if (!approval.canApproveGroup(me == null ? null : me.getIdGroup(), makerGroupOf(c))) continue;
            if (!scope.canSee(me, c.getMaker())) continue;
            if (isStale(c)) continue;
            String label = targetLabel(c.getTarget());
            if ("BULK_EDIT".equals(c.getOp()))
                out.add(new ReviewItem(c, List.of(), parseList(c.getAfterJson()).size(), List.of(), label));
            else if ("IMPORT".equals(c.getOp()))
                out.add(new ReviewItem(c, List.of(), 0, parseList(c.getAfterJson()), label));
            else
                out.add(new ReviewItem(c, diffs(c), 0, List.of(), label));
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

    /** Idem, tapi hanya milik maker yang boleh dilihat user ini (BUG_AUSY_00138/00139). */
    public List<GlChangeRequest> pendingVisible(AusyUserDetails me) {
        return scope.filterBy(me, pendingVisible(), GlChangeRequest::getMaker);
    }
    public long pendingVisibleCount(AusyUserDetails me) { return pendingVisible(me).size(); }

    /** Pending requests older than this are treated as abandoned/junk and auto-hidden. */
    private static final int STALE_DAYS = 7;

    /**
     * A pending change is "stale" (auto-hidden) per QA (Shella): valid pending changes show until
     * approved/rejected, but old junk (leftover from the pre-approval bypass era) is hidden.
     * Matching by paket alone proved unreliable because paket numbers get reused (an old junk ADD
     * to "Paket 03" reappeared when a NEW paket 03 was uploaded — "kadang muncul kadang ngga"), so
     * the primary rule is AGE: anything still pending after {@link #STALE_DAYS} days is abandoned/junk.
     * On top of that, EDIT/DELETE are also hidden the moment their target GL row is gone/soft-deleted.
     */
    private boolean isStale(GlChangeRequest c) {
        // abandoned/junk: pending far too long (reliable — not affected by paket-number reuse)
        if (c.getCreatedAt() != null && c.getCreatedAt().isBefore(LocalDateTime.now().minusDays(STALE_DAYS)))
            return true;
        String op = c.getOp();
        if ("EDIT".equals(op) || "DELETE".equals(op)) {
            Long id = c.getGlId();
            if (id == null) return false;
            return gl.findById(id).map(g -> g.getActive() != null && g.getActive() == 0).orElse(true);
        }
        return false;   // recent ADD / BULK_EDIT always shown
    }

    private List<Diff> diffs(GlChangeRequest c) {
        Map<String,String> b = parse(c.getBeforeJson()), a = parse(c.getAfterJson());
        List<Diff> list = new ArrayList<>();
        for (var e : fieldsOf(c.getTarget()).entrySet()) {
            String bv = b==null?null:b.get(e.getKey());
            String av = a==null?null:a.get(e.getKey());
            list.add(new Diff(e.getValue(),
                    bv==null?"—":fmt(c.getTarget(), e.getKey(), bv),
                    av==null?"—":fmt(c.getTarget(), e.getKey(), av),
                    !Objects.equals(bv,av)));
        }
        return list;
    }

    /** Raw stored value -> what the Checker should read. */
    private static String fmt(String target, String key, String v) {
        if (T_GL_CODE.equals(target)) {
            if (key.equals("position")) return "C".equals(v) ? "Credit" : ("D".equals(v) ? "Debit" : v);
            if (key.equals("status"))   return "1".equals(v) ? "Active" : ("0".equals(v) ? "Non Active" : v);
        }
        return v;
    }

    @Transactional
    public Res approve(Long crId, AusyUserDetails me, String note) {
        GlChangeRequest c = cr.findById(crId).orElse(null);
        if (c == null || !"PENDING".equals(c.getStatus()))
            return new Res(false, "Permintaan tidak ditemukan / sudah diproses.");
        if (me.getUsername().equals(c.getMaker()))
            return new Res(false, "Maker tidak boleh mengotorisasi permintaannya sendiri.");
        if (!approval.canApproveGroup(me.getIdGroup(), makerGroupOf(c)))
            return new Res(false, "Group Anda tidak berwenang meng-approve permintaan dari maker tersebut.");
        if (!scope.canSee(me, c.getMaker())) return new Res(false, DataScopeService.NOT_MY_MAKER);

        String err = apply(c);
        if (err != null) return new Res(false, err);

        c.setStatus("APPROVED"); c.setChecker(me.getUsername());
        c.setNoteChecker(note); c.setDecidedAt(LocalDateTime.now());
        cr.save(c);
        audit.transaction(me.getUsername(), "GL_CHANGE_APPROVE",
                c.getOp() + " " + targetLabel(c.getTarget()) + " CR#" + c.getId()
                + " (maker=" + c.getMaker() + ") di-approve & diterapkan ke DB");
        return new Res(true, c.getOp() + " " + targetLabel(c.getTarget()) + " disetujui dan diterapkan.");
    }

    // ---- apply to live data: null on success, else the message to show the Checker ----
    private String apply(GlChangeRequest c) {
        String target = c.getTarget() == null ? T_GL_ROW : c.getTarget();
        return switch (target) {
            case T_GL_CODE -> applyGlCode(c);
            case T_PAKET   -> applyPaketDelete(c);
            default        -> applyGlRow(c);
        };
    }

    private String applyGlRow(GlChangeRequest c) {
        switch (c.getOp()) {
            case "ADD" -> applyAdd(parse(c.getAfterJson()), c.getMaker());
            case "EDIT" -> applyEdit(c.getGlId(), parse(c.getAfterJson()));
            case "DELETE" -> applyDelete(c.getGlId());
            case "BULK_EDIT" -> applyBulkEdit(parseList(c.getAfterJson()));
            default -> { return "Operasi tidak dikenal."; }
        }
        return null;
    }

    private String applyGlCode(GlChangeRequest c) {
        Map<String,String> a = parse(c.getAfterJson());
        try {
            switch (c.getOp()) {
                case "ADD" -> {
                    if (a == null) return "Data permintaan tidak terbaca.";
                    String e = glCodeService.add(a.get("code"), a.get("position"), a.get("description"),
                                                 shortOf(a.get("status")), c.getMaker());
                    if ("exists".equals(e)) return "Tidak dapat diterapkan: NoGL + Debit/Credit sudah terdaftar.";
                    if (e != null) return "Tidak dapat diterapkan: data Kode GL tidak valid.";
                }
                case "EDIT" -> {
                    if (a == null || c.getRefId() == null) return "Data permintaan tidak terbaca.";
                    String e = glCodeService.edit(c.getRefId(), a.get("code"), a.get("position"), a.get("description"),
                                                  shortOf(a.get("status")), c.getMaker());
                    if (e != null) return "Tidak dapat diterapkan: Kode GL tidak ditemukan / tidak valid.";
                }
                case "DELETE" -> {
                    if (c.getRefId() == null) return "Data permintaan tidak terbaca.";
                    String e = glCodeService.delete(c.getRefId());
                    if ("inuse".equals(e)) return "Tidak dapat diterapkan: Kode GL sudah dipakai pada transaksi.";
                    if (e != null) return "Tidak dapat diterapkan: Kode GL tidak ditemukan.";
                }
                case "IMPORT" -> glCodeService.applyImport(parseList(c.getAfterJson()), c.getMaker());
                default -> { return "Operasi tidak dikenal."; }
            }
        } catch (GlCodeService.DuplicateGl e) {
            return "Tidak dapat diterapkan: NoGL + Debit/Credit sudah terdaftar.";
        }
        return null;
    }

    private String applyPaketDelete(GlChangeRequest c) {
        if (!"DELETE".equals(c.getOp())) return "Operasi tidak dikenal.";
        if (gl.findBatchRows(c.getNoPaket(), c.getInputer()).isEmpty())
            return "Batch sudah tidak ada / sudah diproses.";
        gl.deletePaket(c.getNoPaket(), c.getInputer());
        return null;
    }

    private static Short shortOf(String s) {
        try { return Short.valueOf(s); } catch (Exception e) { return 1; }
    }

    @Transactional
    public String reject(Long crId, AusyUserDetails me, String note) {
        GlChangeRequest c = cr.findById(crId).orElse(null);
        if (c == null || !"PENDING".equals(c.getStatus())) return "Permintaan tidak ditemukan / sudah diproses.";
        if (me.getUsername().equals(c.getMaker()))
            return "Maker tidak boleh memutuskan permintaannya sendiri.";
        if (!approval.canApproveGroup(me.getIdGroup(), makerGroupOf(c)))
            return "Group Anda tidak berwenang menolak permintaan dari maker tersebut.";
        if (!scope.canSee(me, c.getMaker())) return DataScopeService.NOT_MY_MAKER;
        if (note == null || note.isBlank()) return "Catatan penolakan wajib diisi.";
        // rejected: live GL data is left unchanged (8.3.d)
        c.setStatus("REJECTED"); c.setChecker(me.getUsername());
        c.setNoteChecker(note); c.setDecidedAt(LocalDateTime.now());
        cr.save(c);
        audit.transaction(me.getUsername(), "GL_CHANGE_REJECT",
                c.getOp() + " " + targetLabel(c.getTarget()) + " CR#" + c.getId()
                + " (maker=" + c.getMaker() + ") DITOLAK. Catatan: " + note);
        return "Permintaan ditolak. Data tidak diubah; alasan tercatat di audit trail.";
    }

    // ---- apply to live data ----
    private void applyAdd(Map<String,String> a, String maker) {
        if (a == null) return;
        GlTabel g = new GlTabel();
        String code = n(a.get("gltrac")).trim(), dc = n(a.get("gltrcd")).trim();
        BigDecimal amount = amt(a.get("gltram"));
        g.setGltrac(code); g.setGltrcd(dc);
        g.setGltram(amount); g.setGltrds(a.get("gltrds"));
        g.setNoPaket(a.get("noPaket"));
        g.setGltrus(maker);   // username utuh — lihat BUG_AUSY_00138 di UploadService
        // Baris manual dulu hanya mengisi 5 kolom, sehingga tanggal posting kosong di List GL Aktif
        // dan baris itu masuk ACCTXT tanpa tanggal / nominal ekuivalen / referensi. Lengkapi di sini,
        // memakai bentuk yang sama dengan jalur Upload Excel. Tanggal = saat GL benar-benar aktif
        // (checker approve), karena applyAdd dijalankan pada saat otorisasi.
        String ddmmyy = LocalDate.now().format(DDMMYY);
        g.setGltrpd(ddmmyy); g.setGltrpd1(ddmmyy);
        g.setGleqam(amount); g.setGlotam(amount);
        g.setGlName(glCodeService.descriptionOf(code, dc));
        g.setGltrcy("IDR"); g.setGlotcc("IDR");
        g.setGltrrf(n(a.get("noPaket")).trim() + "-01-" + String.format("%04d", seq.next()));
        // ADD lewat change request sudah di-approve checker -> GL aktif langsung. Antrian Otorisasi
        // Upload GL disaring status_dual='PENDING', jadi baris ini tetap tidak muncul di sana.
        // status_print='BELUM UPLOAD' = belum di-export; kalau ditandai 'SUDAH UPLOAD' baris manual
        // tidak akan pernah bisa diajukan export ke core (BUG_AUSY_00109).
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
