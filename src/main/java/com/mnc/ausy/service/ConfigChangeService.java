package com.mnc.ausy.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mnc.ausy.domain.*;
import com.mnc.ausy.repo.*;
import com.mnc.ausy.security.AusyUserDetails;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;

/**
 * Config dual control — Add/Edit/Delete on Master Data (USER, GROUP, MENU, ACCESS, APPROVAL)
 * under maker/checker. Admin (①) submits a change request WITH A REASON (nothing changes yet);
 * Admin Approval (②) approves/rejects. Only on approve is it applied to the live tables.
 */
@Service
public class ConfigChangeService {

    /** Displayed fields per entity (json key -> label) for the review diff. */
    static final Map<String, LinkedHashMap<String,String>> ENTITY_FIELDS = new LinkedHashMap<>();
    static {
        ENTITY_FIELDS.put("USER", fields("userId","Username","nama","Nama","email","Email","group","Role","status","Status"));
        ENTITY_FIELDS.put("GROUP", fields("namaGroup","Nama Role","inisial","Inisial","level","Level"));
        ENTITY_FIELDS.put("MENU", fields("namaMenu","Nama Menu","url","URL","parent","Parent","urutan","Urutan"));
        ENTITY_FIELDS.put("ACCESS", fields("group","Role","aksesMenu","Menu yang Diberi Akses"));
        ENTITY_FIELDS.put("APPROVAL", fields("approver","Role Penyetuju","target","Berwenang Menyetujui"));
    }
    private static LinkedHashMap<String,String> fields(String... kv){
        LinkedHashMap<String,String> m=new LinkedHashMap<>();
        for(int i=0;i+1<kv.length;i+=2) m.put(kv[i],kv[i+1]);
        return m;
    }
    private static String entityLabel(String e){
        return switch (e==null?"":e){ case "GROUP"->"Role"; case "MENU"->"Menu"; case "ACCESS"->"Access Menu";
            case "APPROVAL"->"Approval Group"; default->"User"; };
    }
    /** Kept for backward compat (GL/user forms may reference it). */
    public static final LinkedHashMap<String,String> FIELDS = ENTITY_FIELDS.get("USER");

    private final UserRepository users;
    private final GroupUserRepository groups;
    private final MenuRepository menuRepo;
    private final GroupMenuRepository groupMenus;
    private final ApprovalGroupRepository approvalRules;
    private final ConfigChangeRequestRepository cr;
    private final ApprovalService approval;
    private final AuditService audit;
    private final PasswordEncoder encoder;
    private final AccessService access;
    private final ObjectMapper json = new ObjectMapper();

    public ConfigChangeService(UserRepository users, GroupUserRepository groups, MenuRepository menuRepo,
                               GroupMenuRepository groupMenus, ApprovalGroupRepository approvalRules,
                               ConfigChangeRequestRepository cr, ApprovalService approval, AuditService audit,
                               PasswordEncoder encoder, AccessService access) {
        this.users = users; this.groups = groups; this.menuRepo = menuRepo; this.groupMenus = groupMenus;
        this.approvalRules = approvalRules; this.cr = cr; this.approval = approval; this.audit = audit; this.encoder = encoder;
        this.access = access;
    }

    private static String n(String s){ return s==null?"":s; }
    /** Tampilkan operasi dalam Bahasa Indonesia (kode internal tetap ADD/EDIT/DELETE). */
    public static String opLabel(String op){
        switch (n(op)) { case "ADD": return "TAMBAH"; case "EDIT": return "UBAH"; case "DELETE": return "HAPUS";
            case "RESET_PASS": return "RESET PASSWORD"; default: return n(op); }
    }
    private static final java.util.regex.Pattern EMAIL =
            java.util.regex.Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");
    private static boolean invalidEmail(String e){ return e != null && !e.isBlank() && !EMAIL.matcher(e.trim()).matches(); }
    private String groupName(Integer id){ return id==null?"":groups.findById(id).map(GroupUser::getNamaGroup).orElse("#"+id); }
    private String groupLevel(Integer id){ return id==null?"USER":groups.findById(id).map(GroupUser::getLevel).orElse("USER"); }
    private String menuName(Integer id){ return id==null?"":menuRepo.findById(id).map(Menu::getNamaMenu).orElse("#"+id); }
    private String statusLabel(Short s){ return (s!=null && s==1) ? "Active" : "Non Active"; }
    private String toJson(Map<String,String> m){ try { return json.writeValueAsString(m); } catch (Exception e){ return "{}"; } }
    @SuppressWarnings("unchecked")
    public Map<String,String> parse(String s){
        if (s==null || s.isBlank()) return null;
        try { return json.readValue(s, LinkedHashMap.class); } catch (Exception e){ return null; }
    }
    private static Integer toInt(String s){ try { return Integer.valueOf(s.trim()); } catch (Exception e){ return null; } }

    /** Target ids of this entity that already have a request waiting for Admin Approval. */
    public Set<String> pendingIds(String entity){
        Set<String> ids = new HashSet<>();
        for (ConfigChangeRequest c : cr.findByStatusOrderByCreatedAtDesc("PENDING"))
            if (n(entity).equals(n(c.getEntity())) && c.getTargetId() != null) ids.add(c.getTargetId());
        return ids;
    }
    /** Is the same approval rule already queued as a pending ADD? */
    private boolean pendingApprovalAdd(Integer approverGroup, Integer targetGroup){
        for (ConfigChangeRequest c : cr.findByStatusOrderByCreatedAtDesc("PENDING")) {
            if (!"APPROVAL".equals(c.getEntity()) || !"ADD".equals(c.getOp())) continue;
            Map<String,String> a = parse(c.getAfterJson());
            if (a != null && String.valueOf(approverGroup).equals(a.get("approverGroup"))
                          && String.valueOf(targetGroup).equals(a.get("targetGroup"))) return true;
        }
        return false;
    }

    private boolean isPending(String entity, Object targetId){
        return targetId != null && pendingIds(entity).contains(String.valueOf(targetId));
    }
    private static String pendingMsg(String entity){
        return entityLabel(entity) + " tersebut masih menunggu otorisasi Admin Approval.";
    }
    /** Same values on both sides? Internal keys (ids, password) are ignored. */
    private static boolean unchanged(Map<String,String> before, Map<String,String> after, String... keys){
        for (String k : keys) if (!Objects.equals(n(before.get(k)).trim(), n(after.get(k)).trim())) return false;
        return true;
    }
    private static final String NO_CHANGE = "Tidak ada perubahan data — permintaan tidak diajukan.";

    private ConfigChangeRequest base(String entity, String op, String targetId, AusyUserDetails me, String note){
        ConfigChangeRequest c = new ConfigChangeRequest();
        c.setEntity(entity); c.setOp(op); c.setTargetId(targetId);
        c.setMaker(me.getUsername()); c.setMakerGroup(me.getIdGroup());
        c.setNoteMaker(note); c.setStatus("PENDING");
        return c;
    }
    private String saveReq(ConfigChangeRequest c, String entity, String op, String what){
        cr.save(c);
        audit.transaction(c.getMaker(), "CONFIG_CHANGE_REQ", opLabel(op) + " " + entityLabel(entity) + " " + what + " diajukan (CR#" + c.getId() + ")");
        return "Permintaan " + opLabel(op) + " " + entityLabel(entity) + " diajukan (menunggu approval Admin Approval).";
    }

    // ================= USER =================
    @Transactional
    public String submitAdd(String userId, String rawPass, String nama, String email, Integer idGroup,
                            AusyUserDetails me, String note) {
        if (note==null || note.isBlank()) return "Alasan (reason) wajib diisi.";
        { String blk = approval.submissionBlockedReason(me.getIdGroup()); if (blk != null) return blk; }
        userId = userId==null?"":userId.toLowerCase().trim();
        if (userId.isEmpty()) return "Username tidak valid.";
        if (users.existsByUserId(userId)) return "Username sudah dipakai.";
        if (invalidEmail(email)) return "Format email tidak valid.";
        Map<String,String> after = new LinkedHashMap<>();
        after.put("userId", userId); after.put("nama", n(nama)); after.put("email", n(email));
        after.put("group", groupName(idGroup)); after.put("status", "Active");
        after.put("idGroup", String.valueOf(idGroup)); after.put("pass", encoder.encode(rawPass));
        ConfigChangeRequest c = base("USER","ADD", null, me, note); c.setAfterJson(toJson(after));
        return saveReq(c, "USER", "ADD", "'" + userId + "'");
    }

    @Transactional
    public String submitEdit(String userId, String nama, String email, Integer idGroup, Short status,
                             String rawPass, AusyUserDetails me, String note) {
        if (note==null || note.isBlank()) return "Alasan (reason) wajib diisi.";
        { String blk = approval.submissionBlockedReason(me.getIdGroup()); if (blk != null) return blk; }
        if (invalidEmail(email)) return "Format email tidak valid.";
        User u = users.findByUserId(userId).orElse(null);
        if (u == null) return "User tidak ditemukan.";
        if (isPending("USER", userId)) return pendingMsg("USER");
        Map<String,String> after = new LinkedHashMap<>();
        after.put("userId", n(u.getUserId())); after.put("nama", n(nama)); after.put("email", n(email));
        after.put("group", groupName(idGroup)); after.put("status", statusLabel(status));
        after.put("idGroup", String.valueOf(idGroup));
        if (rawPass != null && !rawPass.isBlank()) { after.put("pass", encoder.encode(rawPass)); after.put("passChanged", "1"); }
        Map<String,String> before = userMap(u);
        boolean passChanged = "1".equals(after.get("passChanged"));
        if (!passChanged && unchanged(before, after, "userId","nama","email","group","status","idGroup"))
            return NO_CHANGE;

        ConfigChangeRequest c = base("USER","EDIT", userId, me, note);
        c.setBeforeJson(toJson(before)); c.setAfterJson(toJson(after));
        return saveReq(c, "USER", "EDIT", "'" + userId + "'");
    }

    @Transactional
    public String submitDelete(String userId, AusyUserDetails me, String note) {
        if (note==null || note.isBlank()) return "Alasan (reason) wajib diisi.";
        { String blk = approval.submissionBlockedReason(me.getIdGroup()); if (blk != null) return blk; }
        User u = users.findByUserId(userId).orElse(null);
        if (u == null) return "User tidak ditemukan.";
        if (userId.equalsIgnoreCase(me.getUsername())) return "Tidak bisa mengajukan hapus akun sendiri.";
        if (isPending("USER", userId)) return pendingMsg("USER");
        ConfigChangeRequest c = base("USER","DELETE", userId, me, note); c.setBeforeJson(toJson(userMap(u)));
        return saveReq(c, "USER", "DELETE", "'" + userId + "'");
    }
    private Map<String,String> userMap(User u){
        Map<String,String> m = new LinkedHashMap<>();
        m.put("userId", n(u.getUserId())); m.put("nama", n(u.getNama())); m.put("email", n(u.getEmail()));
        m.put("group", groupName(u.getIdGroup())); m.put("status", statusLabel(u.getStatusAccount()));
        m.put("idGroup", u.getIdGroup()==null?"":String.valueOf(u.getIdGroup()));
        return m;
    }

    // ================= GROUP =================
    private Map<String,String> groupMap(GroupUser g){
        Map<String,String> m=new LinkedHashMap<>();
        m.put("namaGroup", n(g.getNamaGroup())); m.put("inisial", n(g.getInisial())); m.put("level", n(g.getLevel()));
        return m;
    }
    @Transactional
    public String submitGroupAdd(String namaGroup, String inisial, String level, AusyUserDetails me, String note){
        if (note==null||note.isBlank()) return "Alasan (reason) wajib diisi.";
        { String blk = approval.submissionBlockedReason(me.getIdGroup()); if (blk != null) return blk; }
        if (namaGroup==null||namaGroup.isBlank()) return "Nama Group wajib diisi.";
        Map<String,String> a=new LinkedHashMap<>(); a.put("namaGroup",namaGroup.trim()); a.put("inisial",n(inisial)); a.put("level",n(level));
        ConfigChangeRequest c=base("GROUP","ADD",null,me,note); c.setAfterJson(toJson(a));
        return saveReq(c,"GROUP","ADD","'"+namaGroup.trim()+"'");
    }
    @Transactional
    public String submitGroupEdit(Integer idGroup, String namaGroup, String inisial, String level, AusyUserDetails me, String note){
        if (note==null||note.isBlank()) return "Alasan (reason) wajib diisi.";
        { String blk = approval.submissionBlockedReason(me.getIdGroup()); if (blk != null) return blk; }
        GroupUser g=groups.findById(idGroup).orElse(null); if(g==null) return "Role tidak ditemukan.";
        if (isPending("GROUP", idGroup)) return pendingMsg("GROUP");
        Map<String,String> a=new LinkedHashMap<>(); a.put("namaGroup",n(namaGroup)); a.put("inisial",n(inisial)); a.put("level",n(level));
        Map<String,String> b=groupMap(g);
        if (unchanged(b, a, "namaGroup","inisial","level")) return NO_CHANGE;
        ConfigChangeRequest c=base("GROUP","EDIT",String.valueOf(idGroup),me,note); c.setBeforeJson(toJson(b)); c.setAfterJson(toJson(a));
        return saveReq(c,"GROUP","EDIT","'"+g.getNamaGroup()+"'");
    }
    @Transactional
    /** Group yang masih punya user — dipakai halaman Group User untuk badge "Terpakai". */
    public Set<Integer> groupsInUse() {
        Set<Integer> out = new LinkedHashSet<>();
        for (GroupUser g : groups.findAllByOrderByNamaGroupAsc())
            if (users.countByIdGroup(g.getIdGroup()) > 0) out.add(g.getIdGroup());
        return out;
    }

    public String submitGroupDelete(Integer idGroup, AusyUserDetails me, String note){
        if (note==null||note.isBlank()) return "Alasan (reason) wajib diisi.";
        { String blk = approval.submissionBlockedReason(me.getIdGroup()); if (blk != null) return blk; }
        GroupUser g=groups.findById(idGroup).orElse(null); if(g==null) return "Role tidak ditemukan.";
        // BUG_AUSY_00111: menghapus group yang masih dipakai membuat user-nya kehilangan seluruh akses
        long dipakai = users.countByIdGroup(idGroup);
        if (dipakai > 0)
            return "Role '"+g.getNamaGroup()+"' masih dipakai "+dipakai+" user — tidak dapat dihapus."
                 + " Pindahkan user tersebut ke group lain lebih dulu.";
        if (isPending("GROUP", idGroup)) return pendingMsg("GROUP");
        ConfigChangeRequest c=base("GROUP","DELETE",String.valueOf(idGroup),me,note); c.setBeforeJson(toJson(groupMap(g)));
        return saveReq(c,"GROUP","DELETE","'"+g.getNamaGroup()+"'");
    }

    /** Apakah aturan ini satu-satunya penyetuju aktif untuk target tersebut? */
    private boolean lastApproverFor(Integer targetGroup, Integer approverGroup) {
        for (ApprovalGroup other : approvalRules.findByTargetGroup(targetGroup)) {
            if (other.getApproverGroup() == null || other.getApproverGroup().equals(approverGroup)) continue;
            if (users.countByIdGroupAndStatusAccount(other.getApproverGroup(), (short) 1) > 0) return false;
        }
        return true;
    }

    // ================= MENU =================
    private Map<String,String> menuMap(Menu m){
        Map<String,String> x=new LinkedHashMap<>();
        x.put("namaMenu",n(m.getNamaMenu())); x.put("url",n(m.getUrl()));
        x.put("parent", m.getParent()==null?"0":String.valueOf(m.getParent()));
        x.put("urutan", m.getUrutan()==null?"0":String.valueOf(m.getUrutan()));
        return x;
    }
    @Transactional
    public String submitMenuAdd(String namaMenu, String url, Integer parent, Integer urutan, AusyUserDetails me, String note){
        if (note==null||note.isBlank()) return "Alasan (reason) wajib diisi.";
        { String blk = approval.submissionBlockedReason(me.getIdGroup()); if (blk != null) return blk; }
        if (namaMenu==null||namaMenu.isBlank()) return "Nama Menu wajib diisi.";
        Map<String,String> a=new LinkedHashMap<>(); a.put("namaMenu",namaMenu.trim()); a.put("url",n(url));
        a.put("parent",String.valueOf(parent==null?0:parent)); a.put("urutan",String.valueOf(urutan==null?0:urutan));
        ConfigChangeRequest c=base("MENU","ADD",null,me,note); c.setAfterJson(toJson(a));
        return saveReq(c,"MENU","ADD","'"+namaMenu.trim()+"'");
    }
    @Transactional
    public String submitMenuEdit(Integer idMenu, String namaMenu, String url, Integer parent, Integer urutan, AusyUserDetails me, String note){
        if (note==null||note.isBlank()) return "Alasan (reason) wajib diisi.";
        { String blk = approval.submissionBlockedReason(me.getIdGroup()); if (blk != null) return blk; }
        Menu m=menuRepo.findById(idMenu).orElse(null); if(m==null) return "Menu tidak ditemukan.";
        if (isPending("MENU", idMenu)) return pendingMsg("MENU");
        Map<String,String> a=new LinkedHashMap<>(); a.put("namaMenu",n(namaMenu)); a.put("url",n(url));
        a.put("parent",String.valueOf(parent==null?0:parent)); a.put("urutan",String.valueOf(urutan==null?0:urutan));
        Map<String,String> b=menuMap(m);
        if (unchanged(b, a, "namaMenu","url","parent","urutan")) return NO_CHANGE;
        ConfigChangeRequest c=base("MENU","EDIT",String.valueOf(idMenu),me,note); c.setBeforeJson(toJson(b)); c.setAfterJson(toJson(a));
        return saveReq(c,"MENU","EDIT","'"+m.getNamaMenu()+"'");
    }
    @Transactional
    public String submitMenuDelete(Integer idMenu, AusyUserDetails me, String note){
        if (note==null||note.isBlank()) return "Alasan (reason) wajib diisi.";
        { String blk = approval.submissionBlockedReason(me.getIdGroup()); if (blk != null) return blk; }
        Menu m=menuRepo.findById(idMenu).orElse(null); if(m==null) return "Menu tidak ditemukan.";
        if (isPending("MENU", idMenu)) return pendingMsg("MENU");
        ConfigChangeRequest c=base("MENU","DELETE",String.valueOf(idMenu),me,note); c.setBeforeJson(toJson(menuMap(m)));
        return saveReq(c,"MENU","DELETE","'"+m.getNamaMenu()+"'");
    }

    // ================= ACCESS (group_menu) =================
    @Transactional
    public String submitAccess(Integer group, List<Integer> menuIds, AusyUserDetails me, String note){
        if (note==null||note.isBlank()) return "Alasan (reason) wajib diisi.";
        { String blk = approval.submissionBlockedReason(me.getIdGroup()); if (blk != null) return blk; }
        if (group==null) return "Group belum dipilih.";
        if (isPending("ACCESS", group)) return pendingMsg("ACCESS");
        Set<Integer> want = menuIds==null? new HashSet<>() : new HashSet<>(menuIds);
        // current granted (leaf menus)
        List<Integer> cur = new ArrayList<>();
        for (GroupMenu gm : groupMenus.findByIdGroup(group)) cur.add(gm.getIdMenu());
        Map<String,String> before = accessMap(group, cur);
        Map<String,String> after  = accessMap(group, new ArrayList<>(want));
        if (unchanged(before, after, "menuIds")) return NO_CHANGE;
        ConfigChangeRequest c=base("ACCESS","EDIT",String.valueOf(group),me,note);
        c.setBeforeJson(toJson(before)); c.setAfterJson(toJson(after));
        return saveReq(c,"ACCESS","EDIT","'"+groupName(group)+"'");
    }
    private Map<String,String> accessMap(Integer group, List<Integer> ids){
        Collections.sort(ids);
        List<String> names=new ArrayList<>(); for(Integer id:ids) names.add(menuName(id));
        Map<String,String> m=new LinkedHashMap<>();
        m.put("group", groupName(group));
        m.put("aksesMenu", names.isEmpty()? "(tidak ada)" : String.join(", ", names));
        m.put("menuIds", ids.stream().map(String::valueOf).reduce((x,y)->x+","+y).orElse(""));
        return m;
    }

    /**
     * BUG_AUSY_00129 — Admin mereset password user sisi operasional (Maker/Checker) lewat tombol
     * sendiri, bukan diselipkan di form Ubah. Tetap dual control: password baru hanya dipasang
     * setelah Admin Approval menyetujui, dan yang tersimpan di permintaan sudah ter-hash.
     */
    @Transactional
    public String submitPasswordReset(String userId, String newPass, AusyUserDetails me, String note){
        if (note==null||note.isBlank()) return "Alasan (reason) wajib diisi.";
        { String blk = approval.submissionBlockedReason(me.getIdGroup()); if (blk != null) return blk; }
        if (newPass==null||newPass.isBlank()) return "Password baru wajib diisi.";
        if (newPass.trim().length() < 6) return "Password baru minimal 6 karakter.";
        User u = users.findByUserId(userId).orElse(null);
        if (u == null) return "User tidak ditemukan.";
        if (isPending("USER", userId)) return pendingMsg("USER");
        if (!resettableByAdmin(u))
            return "Password user " + userId + " tidak dapat direset dari sini — tombol ini hanya untuk"
                 + " user sisi operasional (Maker & Checker).";
        Map<String,String> a=new LinkedHashMap<>();
        a.put("userId", n(u.getUserId())); a.put("nama", n(u.getNama()));
        a.put("pass", encoder.encode(newPass.trim()));
        ConfigChangeRequest c=base("USER","RESET_PASS",userId,me,note); c.setAfterJson(toJson(a));
        return saveReq(c,"USER","RESET_PASS","'"+userId+"'");
    }

    /** User sisi operasional (Maker/Checker) — hanya mereka yang passwordnya boleh direset Admin. */
    public boolean resettableByAdmin(User u) {
        return u != null && "BUSINESS".equals(access.sideOf(u.getIdGroup()));
    }

    // ================= APPROVAL (approval_group) =================
    /**
     * BUG_AUSY_00128 — jalan keluar kalau rantai otorisasi putus.
     *
     * Semua perubahan config wajib lewat penyetuju. Kalau group penyetujunya hilang (mis. role
     * Admin Approval lama dihapus dan diganti yang baru), admin tidak bisa mengajukan apa pun —
     * termasuk memasang penyetuju baru, jadi sistemnya mati kunci. Karena itu, SELAMA group
     * pengaju belum punya penyetuju aktif, penambahan aturan Approval Group diterapkan langsung
     * dan dicatat khusus di audit trail. Begitu penyetuju ada, semuanya kembali lewat dual control.
     */
    @Transactional
    public String submitApprovalAdd(Integer approverGroup, Integer targetGroup, AusyUserDetails me, String note){
        if (note==null||note.isBlank()) return "Alasan (reason) wajib diisi.";
        boolean breakGlass = !approval.hasActiveApprover(me.getIdGroup());
        if (approverGroup==null||targetGroup==null) return "Group belum lengkap.";
        if (approverGroup.equals(targetGroup)) return "Approver dan target tidak boleh group yang sama.";
        if (approvalRules.existsByApproverGroupAndTargetGroup(approverGroup, targetGroup)) return "Aturan tersebut sudah ada.";
        if (pendingApprovalAdd(approverGroup, targetGroup))
            return "Aturan tersebut sudah diajukan dan menunggu otorisasi Admin Approval.";
        Map<String,String> a=new LinkedHashMap<>();
        a.put("approver", groupName(approverGroup)); a.put("target", groupName(targetGroup));
        a.put("approverGroup", String.valueOf(approverGroup)); a.put("targetGroup", String.valueOf(targetGroup));
        if (breakGlass) {
            ApprovalGroup r = new ApprovalGroup();
            r.setApproverGroup(approverGroup); r.setTargetGroup(targetGroup);
            approvalRules.save(r);
            audit.transaction(me.getUsername(), "APPROVAL_BREAKGLASS",
                    "Aturan " + groupName(approverGroup) + " → " + groupName(targetGroup) + " dipasang LANGSUNG"
                  + " karena group " + groupName(me.getIdGroup()) + " belum punya penyetuju aktif. Alasan: " + note);
            return "Aturan approval dipasang langsung karena group Anda belum punya penyetuju aktif"
                 + " (tercatat di audit trail). Perubahan config berikutnya kembali butuh otorisasi.";
        }
        ConfigChangeRequest c=base("APPROVAL","ADD",null,me,note); c.setAfterJson(toJson(a));
        return saveReq(c,"APPROVAL","ADD","("+groupName(approverGroup)+" → "+groupName(targetGroup)+")");
    }
    @Transactional
    public String submitApprovalDelete(Long ruleId, AusyUserDetails me, String note){
        if (note==null||note.isBlank()) return "Alasan (reason) wajib diisi.";
        { String blk = approval.submissionBlockedReason(me.getIdGroup()); if (blk != null) return blk; }
        ApprovalGroup r=approvalRules.findById(ruleId).orElse(null); if(r==null) return "Aturan tidak ditemukan.";
        if (isPending("APPROVAL", ruleId)) return pendingMsg("APPROVAL");
        // Melepas aturan terakhir membuat group target tidak bisa mengajukan apa pun (BUG_AUSY_00128).
        if (lastApproverFor(r.getTargetGroup(), r.getApproverGroup()))
            return "Aturan ini satu-satunya penyetuju untuk " + groupName(r.getTargetGroup())
                 + ". Pasang penyetuju pengganti lebih dulu, baru aturan ini bisa dilepas.";
        Map<String,String> b=new LinkedHashMap<>();
        b.put("approver", groupName(r.getApproverGroup())); b.put("target", groupName(r.getTargetGroup()));
        ConfigChangeRequest c=base("APPROVAL","DELETE",String.valueOf(ruleId),me,note); c.setBeforeJson(toJson(b));
        return saveReq(c,"APPROVAL","DELETE","("+groupName(r.getApproverGroup())+" → "+groupName(r.getTargetGroup())+")");
    }

    // ================= Admin Approval (②) review =================
    public record Diff(String label, String before, String after, boolean changed) {}
    public record ReviewItem(ConfigChangeRequest cr, List<Diff> diffs, String entityLabel, String opLabel) {}

    public List<ReviewItem> pendingReviewFor(Integer approverGroup) {
        List<ReviewItem> out = new ArrayList<>();
        for (ConfigChangeRequest c : cr.findByStatusOrderByCreatedAtDesc("PENDING")) {
            if (!approval.canApprove(approverGroup, c.getMaker())) continue;
            out.add(new ReviewItem(c, diffs(c), entityLabel(c.getEntity()), opLabel(c.getOp())));
        }
        return out;
    }

    /** One section per Master Data entity, fixed order; only non-empty sections returned. */
    public record ReviewSection(String entity, String label, List<ReviewItem> items) {}
    private static final String[] SECTION_ORDER = {"USER","GROUP","MENU","ACCESS","APPROVAL"};
    public List<ReviewSection> pendingReviewSectionsFor(Integer approverGroup) {
        Map<String,List<ReviewItem>> byEntity = new LinkedHashMap<>();
        for (ReviewItem it : pendingReviewFor(approverGroup))
            byEntity.computeIfAbsent(n(it.cr().getEntity()).isEmpty() ? "USER" : n(it.cr().getEntity()), k -> new ArrayList<>()).add(it);
        List<ReviewSection> sections = new ArrayList<>();
        for (String e : SECTION_ORDER)
            if (byEntity.containsKey(e)) sections.add(new ReviewSection(e, entityLabel(e), byEntity.remove(e)));
        // any unexpected entity codes appended at the end
        for (var e : byEntity.entrySet()) sections.add(new ReviewSection(e.getKey(), entityLabel(e.getKey()), e.getValue()));
        return sections;
    }

    private List<Diff> diffs(ConfigChangeRequest c) {
        var fields = ENTITY_FIELDS.getOrDefault(c.getEntity(), ENTITY_FIELDS.get("USER"));
        Map<String,String> b = parse(c.getBeforeJson()), a = parse(c.getAfterJson());
        List<Diff> list = new ArrayList<>();
        for (var e : fields.entrySet()) {
            String bv = b==null?null:b.get(e.getKey());
            String av = a==null?null:a.get(e.getKey());
            list.add(new Diff(e.getValue(), bv==null?"—":bv, av==null?"—":av, !Objects.equals(bv,av)));
        }
        if ("USER".equals(c.getEntity()) && a != null && "1".equals(a.get("passChanged")))
            list.add(new Diff("Password", "—", "(diubah)", true));
        return list;
    }

    public long countPending(){ return cr.countByStatus("PENDING"); }

    /**
     * Jumlah & daftar yang benar-benar bisa disetujui group ini — dasar yang sama dengan halaman
     * Otorisasi. Dashboard dulu memakai hitungan SELURUH permintaan pending, jadi angkanya muncul
     * padahal halamannya kosong karena tersaring aturan Approval Group (BUG_AUSY_00128).
     */
    public long countPendingFor(Integer approverGroup) { return pendingReviewFor(approverGroup).size(); }

    public List<PendingUser> pendingListFor(Integer approverGroup) {
        List<PendingUser> out = new ArrayList<>();
        for (ConfigChangeRequest c : cr.findByStatusOrderByCreatedAtDesc("PENDING")) {
            if (!approval.canApprove(approverGroup, c.getMaker())) continue;
            out.add(new PendingUser(c, targetLabel(c), entityLabel(c.getEntity()), opLabel(c.getOp())));
        }
        return out;
    }

    /** Pengajuan yang dibuat group ini sendiri — untuk sisi pengaju (Admin) melihat miliknya. */
    public List<PendingUser> pendingListByMakerGroup(Integer makerGroup) {
        List<PendingUser> out = new ArrayList<>();
        for (ConfigChangeRequest c : cr.findByStatusOrderByCreatedAtDesc("PENDING")) {
            if (makerGroup == null || !makerGroup.equals(c.getMakerGroup())) continue;
            out.add(new PendingUser(c, targetLabel(c), entityLabel(c.getEntity()), opLabel(c.getOp())));
        }
        return out;
    }

    /** Apakah group ini terdaftar sebagai penyetuju untuk group mana pun? */
    public boolean isApproverForAnyone(Integer group) { return approval.isApprover(group); }

    /** Pending config changes for the Admin home "Perubahan Config Pending" section. */
    public record PendingUser(ConfigChangeRequest cr, String targetLabel, String entityLabel, String opLabel) {}
    public List<PendingUser> pendingList() {
        List<PendingUser> out = new ArrayList<>();
        for (ConfigChangeRequest c : cr.findByStatusOrderByCreatedAtDesc("PENDING"))
            out.add(new PendingUser(c, targetLabel(c), entityLabel(c.getEntity()), opLabel(c.getOp())));
        return out;
    }
    private String targetLabel(ConfigChangeRequest c){
        Map<String,String> a = parse(c.getAfterJson()), b = parse(c.getBeforeJson());
        Map<String,String> ref = a!=null?a:b;
        return switch (n(c.getEntity())) {
            case "GROUP"    -> ref!=null && ref.get("namaGroup")!=null ? ref.get("namaGroup") : ("Group #"+n(c.getTargetId()));
            case "MENU"     -> ref!=null && ref.get("namaMenu")!=null ? ref.get("namaMenu") : ("Menu #"+n(c.getTargetId()));
            case "ACCESS"   -> ref!=null && ref.get("group")!=null ? ref.get("group") : ("Group #"+n(c.getTargetId()));
            case "APPROVAL" -> ref!=null ? n(ref.get("approver"))+" → "+n(ref.get("target")) : "Aturan #"+n(c.getTargetId());
            default -> {
                String t=c.getTargetId();
                if(t==null||t.isBlank()) yield (a!=null && a.get("userId")!=null)? a.get("userId") : "(baru)";
                yield t;
            }
        };
    }

    @Transactional
    public String approve(Long crId, AusyUserDetails me, String note) {
        ConfigChangeRequest c = cr.findById(crId).orElse(null);
        if (c == null || !"PENDING".equals(c.getStatus())) return "Permintaan tidak ditemukan / sudah diproses.";
        if (!approval.canApprove(me.getIdGroup(), c.getMaker()))
            return "Group Anda tidak berwenang meng-approve permintaan config ini.";
        String applyMsg = switch (n(c.getEntity())) {
            case "GROUP"    -> applyGroup(c);
            case "MENU"     -> applyMenu(c);
            case "ACCESS"   -> applyAccess(c);
            case "APPROVAL" -> applyApproval(c);
            default          -> applyUser(c);
        };
        if (applyMsg != null) return applyMsg;   // apply failed -> keep pending
        c.setStatus("APPROVED"); c.setChecker(me.getUsername());
        c.setNoteChecker(note); c.setDecidedAt(LocalDateTime.now());
        cr.save(c);
        audit.transaction(me.getUsername(), "CONFIG_CHANGE_APPROVE",
                opLabel(c.getOp()) + " " + entityLabel(c.getEntity()) + " CR#" + c.getId() + " (maker=" + c.getMaker() + ") di-approve & diterapkan");
        return opLabel(c.getOp()) + " " + entityLabel(c.getEntity()) + " disetujui dan diterapkan.";
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
                opLabel(c.getOp()) + " " + entityLabel(c.getEntity()) + " CR#" + c.getId() + " (maker=" + c.getMaker() + ") DITOLAK. Catatan: " + note);
        return "Permintaan ditolak. Data tidak diubah; alasan tercatat di audit trail.";
    }

    // ================= apply to live tables =================
    private Integer gid(Map<String,String> m){ return toInt(n(m.get("idGroup"))); }

    private String applyUser(ConfigChangeRequest c){
        return switch (c.getOp()) {
            case "ADD" -> applyUserAdd(parse(c.getAfterJson()), c.getMaker());
            case "EDIT" -> applyUserEdit(c.getTargetId(), parse(c.getAfterJson()));
            case "DELETE" -> applyUserDelete(c.getTargetId());
            case "RESET_PASS" -> applyPasswordReset(c.getTargetId(), parse(c.getAfterJson()));
            default -> "Operasi tidak dikenal.";
        };
    }
    private String applyUserAdd(Map<String,String> a, String maker) {
        if (a == null) return "Data permintaan rusak.";
        String uid = a.get("userId");
        if (users.existsByUserId(uid)) return "Username '" + uid + "' sudah dipakai (tidak bisa di-approve).";
        Integer idGroup = gid(a);
        User u = new User();
        u.setUserId(uid); u.setPass(a.get("pass")); u.setNama(a.get("nama")); u.setEmail(a.get("email"));
        u.setIdGroup(idGroup); u.setLevel(groupLevel(idGroup));
        u.setStatusAccount((short)1); u.setCreDate(LocalDateTime.now()); u.setAddby(maker);
        users.save(u); return null;
    }
    private String applyPasswordReset(String targetId, Map<String,String> a) {
        if (a == null || a.get("pass") == null) return "Data permintaan rusak.";
        User u = users.findByUserId(targetId).orElse(null);
        if (u == null) return "User '" + targetId + "' sudah tidak ada.";
        u.setPass(a.get("pass"));
        users.save(u);
        return null;
    }
    private String applyUserEdit(String targetId, Map<String,String> a) {
        if (a == null) return "Data permintaan rusak.";
        User u = users.findByUserId(targetId).orElse(null);
        if (u == null) return "User '" + targetId + "' sudah tidak ada.";
        Integer idGroup = gid(a);
        u.setNama(a.get("nama")); u.setEmail(a.get("email"));
        u.setIdGroup(idGroup); u.setLevel(groupLevel(idGroup));
        u.setStatusAccount("Active".equals(a.get("status")) ? (short)1 : (short)0);
        if (a.get("pass") != null && !a.get("pass").isBlank()) u.setPass(a.get("pass"));
        users.save(u); return null;
    }
    private String applyUserDelete(String targetId) {
        User u = users.findByUserId(targetId).orElse(null);
        if (u == null) return "User '" + targetId + "' sudah tidak ada.";
        users.delete(u); return null;
    }

    private String applyGroup(ConfigChangeRequest c){
        Map<String,String> a = parse(c.getAfterJson());
        switch (c.getOp()) {
            case "ADD" -> { if(a==null) return "Data rusak."; GroupUser g=new GroupUser();
                g.setNamaGroup(a.get("namaGroup")); g.setInisial(a.get("inisial")); g.setLevel(a.get("level")); groups.save(g); }
            case "EDIT" -> { GroupUser g=groups.findById(toInt(c.getTargetId())).orElse(null);
                if(g==null) return "Role sudah tidak ada."; if(a==null) return "Data rusak.";
                g.setNamaGroup(a.get("namaGroup")); g.setInisial(a.get("inisial")); g.setLevel(a.get("level")); groups.save(g); }
            case "DELETE" -> { Integer id=toInt(c.getTargetId());
                if(id!=null){
                    long dipakai = users.countByIdGroup(id);      // bisa berubah sejak pengajuan
                    if (dipakai > 0) return "Tidak dapat diterapkan: role masih dipakai "+dipakai+" user.";
                    // Hak akses menu (group_menu) punya foreign key ke group ini, dan aturan
                    // approval_group menyebut id-nya. Keduanya harus dibersihkan dulu; kalau tidak,
                    // DELETE ditolak database (error 500) atau menyisakan aturan approval yatim.
                    groupMenus.deleteByIdGroup(id);
                    approvalRules.deleteByApproverGroupOrTargetGroup(id, id);
                    if (groups.existsById(id)) groups.deleteById(id);
                } }
            default -> { return "Operasi tidak dikenal."; }
        }
        return null;
    }
    private String applyMenu(ConfigChangeRequest c){
        Map<String,String> a = parse(c.getAfterJson());
        switch (c.getOp()) {
            case "ADD" -> { if(a==null) return "Data rusak."; Menu m=new Menu();
                m.setNamaMenu(a.get("namaMenu")); String u=a.get("url"); m.setUrl(u==null||u.isBlank()?null:u.trim());
                m.setParent(toInt(a.getOrDefault("parent","0"))); m.setUrutan(toInt(a.getOrDefault("urutan","0"))); m.setActive((short)1); menuRepo.save(m); }
            case "EDIT" -> { Menu m=menuRepo.findById(toInt(c.getTargetId())).orElse(null);
                if(m==null) return "Menu sudah tidak ada."; if(a==null) return "Data rusak.";
                m.setNamaMenu(a.get("namaMenu")); String u=a.get("url"); m.setUrl(u==null||u.isBlank()?null:u.trim());
                m.setParent(toInt(a.getOrDefault("parent","0"))); m.setUrutan(toInt(a.getOrDefault("urutan","0"))); menuRepo.save(m); }
            case "DELETE" -> { Integer id=toInt(c.getTargetId()); if(id!=null){ groupMenus.deleteByIdMenu(id); if(menuRepo.existsById(id)) menuRepo.deleteById(id); } }
            default -> { return "Operasi tidak dikenal."; }
        }
        return null;
    }
    private String applyAccess(ConfigChangeRequest c){
        Map<String,String> a = parse(c.getAfterJson());
        Integer group = toInt(c.getTargetId());
        if (a==null || group==null) return "Data rusak.";
        Set<Integer> want = new HashSet<>();
        String ids = a.get("menuIds");
        if (ids!=null && !ids.isBlank()) for(String s: ids.split(",")) { Integer id=toInt(s); if(id!=null) want.add(id); }
        for (Menu m : menuRepo.findAllByOrderByParentAscUrutanAsc()) {
            if (m.getParent()==0) continue;                       // only leaf menus
            boolean has = groupMenus.existsByIdGroupAndIdMenu(group, m.getIdMenu());
            boolean w   = want.contains(m.getIdMenu());
            if (w && !has) { GroupMenu gm=new GroupMenu(); gm.setIdGroup(group); gm.setIdMenu(m.getIdMenu()); groupMenus.save(gm); }
            else if (!w && has) groupMenus.deleteByIdGroupAndIdMenu(group, m.getIdMenu());
        }
        return null;
    }
    private String applyApproval(ConfigChangeRequest c){
        Map<String,String> a = parse(c.getAfterJson());
        switch (c.getOp()) {
            case "ADD" -> { if(a==null) return "Data rusak.";
                Integer ap=toInt(a.get("approverGroup")), tg=toInt(a.get("targetGroup"));
                if(ap==null||tg==null) return "Data rusak.";
                if(!approvalRules.existsByApproverGroupAndTargetGroup(ap,tg)){ ApprovalGroup r=new ApprovalGroup(); r.setApproverGroup(ap); r.setTargetGroup(tg); approvalRules.save(r); } }
            case "DELETE" -> { Long id=null; try{ id=Long.valueOf(c.getTargetId()); }catch(Exception ignore){}
                if(id!=null && approvalRules.existsById(id)) approvalRules.deleteById(id); }
            default -> { return "Operasi tidak dikenal."; }
        }
        return null;
    }
}
