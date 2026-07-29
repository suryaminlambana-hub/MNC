package com.mnc.ausy.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mnc.ausy.domain.ConfigChangeRequest;
import com.mnc.ausy.domain.GroupUser;
import com.mnc.ausy.domain.User;
import com.mnc.ausy.repo.ConfigChangeRequestRepository;
import com.mnc.ausy.repo.GroupUserRepository;
import com.mnc.ausy.repo.UserRepository;
import com.mnc.ausy.security.AusyUserDetails;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;

/**
 * Config dual control — Add/Edit/Delete USER under maker/checker.
 * Admin (①) submits a change request WITH A REASON (data unchanged until approval);
 * Admin Approval (②) approves/rejects. Only on approve is it applied to the users table.
 */
@Service
public class ConfigChangeService {

    /** Displayed fields (key -> label). */
    public static final LinkedHashMap<String,String> FIELDS = new LinkedHashMap<>();
    static {
        FIELDS.put("userId", "Username");
        FIELDS.put("nama",   "Nama");
        FIELDS.put("email",  "Email");
        FIELDS.put("group",  "Group");
        FIELDS.put("status", "Status");
    }

    private final UserRepository users;
    private final GroupUserRepository groups;
    private final ConfigChangeRequestRepository cr;
    private final ApprovalService approval;
    private final AuditService audit;
    private final PasswordEncoder encoder;
    private final ObjectMapper json = new ObjectMapper();

    public ConfigChangeService(UserRepository users, GroupUserRepository groups, ConfigChangeRequestRepository cr,
                               ApprovalService approval, AuditService audit, PasswordEncoder encoder) {
        this.users = users; this.groups = groups; this.cr = cr;
        this.approval = approval; this.audit = audit; this.encoder = encoder;
    }

    private static String n(String s){ return s==null?"":s; }
    private String groupName(Integer id){ return id==null?"":groups.findById(id).map(GroupUser::getNamaGroup).orElse("#"+id); }
    private String groupLevel(Integer id){ return id==null?"USER":groups.findById(id).map(GroupUser::getLevel).orElse("USER"); }
    private String statusLabel(Short s){ return (s!=null && s==1) ? "Active" : "Non Active"; }
    private String toJson(Map<String,String> m){ try { return json.writeValueAsString(m); } catch (Exception e){ return "{}"; } }
    @SuppressWarnings("unchecked")
    public Map<String,String> parse(String s){
        if (s==null || s.isBlank()) return null;
        try { return json.readValue(s, LinkedHashMap.class); } catch (Exception e){ return null; }
    }

    private Map<String,String> userMap(User u){
        Map<String,String> m = new LinkedHashMap<>();
        m.put("userId", n(u.getUserId())); m.put("nama", n(u.getNama())); m.put("email", n(u.getEmail()));
        m.put("group", groupName(u.getIdGroup())); m.put("status", statusLabel(u.getStatusAccount()));
        m.put("idGroup", u.getIdGroup()==null?"":String.valueOf(u.getIdGroup()));
        return m;
    }

    // ---------- Maker (admin ①) submits — NOTHING applied yet ----------
    @Transactional
    public String submitAdd(String userId, String rawPass, String nama, String email, Integer idGroup,
                            AusyUserDetails me, String note) {
        if (note==null || note.isBlank()) return "Alasan (reason) wajib diisi.";
        userId = userId==null?"":userId.toLowerCase().trim();
        if (userId.isEmpty()) return "Username tidak valid.";
        if (users.existsByUserId(userId)) return "Username sudah dipakai.";
        Map<String,String> after = new LinkedHashMap<>();
        after.put("userId", userId); after.put("nama", n(nama)); after.put("email", n(email));
        after.put("group", groupName(idGroup)); after.put("status", "Active");
        after.put("idGroup", String.valueOf(idGroup));
        after.put("pass", encoder.encode(rawPass));           // store encoded, never raw
        ConfigChangeRequest c = base("ADD", null, me, note);
        c.setAfterJson(toJson(after));
        cr.save(c);
        audit.transaction(me.getUsername(), "CONFIG_CHANGE_REQ", "ADD user '" + userId + "' diajukan (CR#" + c.getId() + ")");
        return "Permintaan TAMBAH user diajukan (menunggu approval Admin Approval).";
    }

    @Transactional
    public String submitEdit(String userId, String nama, String email, Integer idGroup, Short status,
                             String rawPass, AusyUserDetails me, String note) {
        if (note==null || note.isBlank()) return "Alasan (reason) wajib diisi.";
        User u = users.findByUserId(userId).orElse(null);
        if (u == null) return "User tidak ditemukan.";
        Map<String,String> after = new LinkedHashMap<>();
        after.put("userId", n(u.getUserId())); after.put("nama", n(nama)); after.put("email", n(email));
        after.put("group", groupName(idGroup)); after.put("status", statusLabel(status));
        after.put("idGroup", String.valueOf(idGroup));
        if (rawPass != null && !rawPass.isBlank()) { after.put("pass", encoder.encode(rawPass)); after.put("passChanged", "1"); }
        ConfigChangeRequest c = base("EDIT", userId, me, note);
        c.setBeforeJson(toJson(userMap(u)));
        c.setAfterJson(toJson(after));
        cr.save(c);
        audit.transaction(me.getUsername(), "CONFIG_CHANGE_REQ", "EDIT user '" + userId + "' diajukan (CR#" + c.getId() + ")");
        return "Permintaan UBAH user diajukan (menunggu approval Admin Approval).";
    }

    @Transactional
    public String submitDelete(String userId, AusyUserDetails me, String note) {
        if (note==null || note.isBlank()) return "Alasan (reason) wajib diisi.";
        User u = users.findByUserId(userId).orElse(null);
        if (u == null) return "User tidak ditemukan.";
        if (userId.equalsIgnoreCase(me.getUsername())) return "Tidak bisa mengajukan hapus akun sendiri.";
        ConfigChangeRequest c = base("DELETE", userId, me, note);
        c.setBeforeJson(toJson(userMap(u)));
        cr.save(c);
        audit.transaction(me.getUsername(), "CONFIG_CHANGE_REQ", "DELETE user '" + userId + "' diajukan (CR#" + c.getId() + ")");
        return "Permintaan HAPUS user diajukan (menunggu approval Admin Approval).";
    }

    private ConfigChangeRequest base(String op, String targetId, AusyUserDetails me, String note){
        ConfigChangeRequest c = new ConfigChangeRequest();
        c.setEntity("USER"); c.setOp(op); c.setTargetId(targetId);
        c.setMaker(me.getUsername()); c.setMakerGroup(me.getIdGroup());
        c.setNoteMaker(note); c.setStatus("PENDING");
        return c;
    }

    // ---------- Admin Approval (②) review ----------
    public record Diff(String label, String before, String after, boolean changed) {}
    public record ReviewItem(ConfigChangeRequest cr, List<Diff> diffs) {}

    public List<ReviewItem> pendingReviewFor(Integer approverGroup) {
        List<ReviewItem> out = new ArrayList<>();
        for (ConfigChangeRequest c : cr.findByStatusOrderByCreatedAtDesc("PENDING")) {
            if (!approval.canApprove(approverGroup, c.getMaker())) continue;
            out.add(new ReviewItem(c, diffs(c)));
        }
        return out;
    }

    private List<Diff> diffs(ConfigChangeRequest c) {
        Map<String,String> b = parse(c.getBeforeJson()), a = parse(c.getAfterJson());
        List<Diff> list = new ArrayList<>();
        for (var e : FIELDS.entrySet()) {
            String bv = b==null?null:b.get(e.getKey());
            String av = a==null?null:a.get(e.getKey());
            list.add(new Diff(e.getValue(), bv==null?"—":bv, av==null?"—":av, !Objects.equals(bv,av)));
        }
        if (a != null && "1".equals(a.get("passChanged")))
            list.add(new Diff("Password", "—", "(diubah)", true));
        return list;
    }

    public long countPending(){ return cr.countByStatus("PENDING"); }

    @Transactional
    public String approve(Long crId, AusyUserDetails me, String note) {
        ConfigChangeRequest c = cr.findById(crId).orElse(null);
        if (c == null || !"PENDING".equals(c.getStatus())) return "Permintaan tidak ditemukan / sudah diproses.";
        if (!approval.canApprove(me.getIdGroup(), c.getMaker()))
            return "Group Anda tidak berwenang meng-approve permintaan config ini.";
        String applyMsg = switch (c.getOp()) {
            case "ADD" -> applyAdd(parse(c.getAfterJson()), c.getMaker());
            case "EDIT" -> applyEdit(c.getTargetId(), parse(c.getAfterJson()));
            case "DELETE" -> applyDelete(c.getTargetId());
            default -> "Operasi tidak dikenal.";
        };
        if (applyMsg != null) return applyMsg;   // apply failed -> keep pending
        c.setStatus("APPROVED"); c.setChecker(me.getUsername());
        c.setNoteChecker(note); c.setDecidedAt(LocalDateTime.now());
        cr.save(c);
        audit.transaction(me.getUsername(), "CONFIG_CHANGE_APPROVE",
                c.getOp() + " user CR#" + c.getId() + " (maker=" + c.getMaker() + ") di-approve & diterapkan");
        return c.getOp() + " user disetujui dan diterapkan.";
    }

    @Transactional
    public String reject(Long crId, AusyUserDetails me, String note) {
        ConfigChangeRequest c = cr.findById(crId).orElse(null);
        if (c == null || !"PENDING".equals(c.getStatus())) return "Permintaan tidak ditemukan / sudah diproses.";
        if (!approval.canApprove(me.getIdGroup(), c.getMaker()))
            return "Group Anda tidak berwenang menolak permintaan config ini.";
        if (note == null || note.isBlank()) return "Catatan penolakan wajib diisi.";
        c.setStatus("REJECTED"); c.setChecker(me.getUsername());
        c.setNoteChecker(note); c.setDecidedAt(LocalDateTime.now());
        cr.save(c);
        audit.transaction(me.getUsername(), "CONFIG_CHANGE_REJECT",
                c.getOp() + " user CR#" + c.getId() + " (maker=" + c.getMaker() + ") DITOLAK. Catatan: " + note);
        return "Permintaan ditolak. Data user tidak diubah; alasan tercatat di audit trail.";
    }

    // ---------- apply to live users ----------
    private Integer gid(Map<String,String> m){ try { return Integer.valueOf(m.get("idGroup")); } catch (Exception e){ return null; } }

    private String applyAdd(Map<String,String> a, String maker) {
        if (a == null) return "Data permintaan rusak.";
        String uid = a.get("userId");
        if (users.existsByUserId(uid)) return "Username '" + uid + "' sudah dipakai (tidak bisa di-approve).";
        Integer idGroup = gid(a);
        User u = new User();
        u.setUserId(uid); u.setPass(a.get("pass")); u.setNama(a.get("nama")); u.setEmail(a.get("email"));
        u.setIdGroup(idGroup); u.setLevel(groupLevel(idGroup));
        u.setStatusAccount((short)1); u.setCreDate(LocalDateTime.now()); u.setAddby(maker);
        users.save(u);
        return null;
    }

    private String applyEdit(String targetId, Map<String,String> a) {
        if (a == null) return "Data permintaan rusak.";
        User u = users.findByUserId(targetId).orElse(null);
        if (u == null) return "User '" + targetId + "' sudah tidak ada.";
        Integer idGroup = gid(a);
        u.setNama(a.get("nama")); u.setEmail(a.get("email"));
        u.setIdGroup(idGroup); u.setLevel(groupLevel(idGroup));
        u.setStatusAccount("Active".equals(a.get("status")) ? (short)1 : (short)0);
        if (a.get("pass") != null && !a.get("pass").isBlank()) u.setPass(a.get("pass"));
        users.save(u);
        return null;
    }

    private String applyDelete(String targetId) {
        User u = users.findByUserId(targetId).orElse(null);
        if (u == null) return "User '" + targetId + "' sudah tidak ada.";
        users.delete(u);
        return null;
    }
}
