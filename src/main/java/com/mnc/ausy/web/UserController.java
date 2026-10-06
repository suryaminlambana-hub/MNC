package com.mnc.ausy.web;

import com.mnc.ausy.domain.GroupUser;
import com.mnc.ausy.domain.User;
import com.mnc.ausy.repo.GroupUserRepository;
import com.mnc.ausy.repo.UserRepository;
import com.mnc.ausy.security.AusyUserDetails;
import com.mnc.ausy.service.ConfigChangeService;
import com.mnc.ausy.service.PasswordPolicyService;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Set;
import java.util.List;
import java.util.Map;

@Controller
@RequestMapping("/users")
public class UserController {
    private final UserRepository users;
    private final GroupUserRepository groups;
    private final ConfigChangeService configChange;
    private final PasswordPolicyService passwordPolicy;

    public UserController(UserRepository users, GroupUserRepository groups, ConfigChangeService configChange,
                          PasswordPolicyService passwordPolicy) {
        this.users = users; this.groups = groups; this.configChange = configChange;
        this.passwordPolicy = passwordPolicy;
    }

    @GetMapping
    public String list(Model model, @AuthenticationPrincipal AusyUserDetails me) {
        model.addAttribute("users", users.findAll());
        List<GroupUser> allGroups = groups.findAllByOrderByNamaGroupAsc();
        // Seluruh role bisa dipilih, termasuk CONFIG APPROVER (BUG_AUSY_00194).
        //
        // Dulu role yang mengotorisasi kita disembunyikan dari sini (BUG_AUSY_00132). Akibatnya
        // Admin tidak bisa mengajukan CONFIG APPROVER kedua sama sekali — padahal justru itu yang
        // dibutuhkan: begitu satu-satunya approver non-aktif, seluruh pengajuan config berhenti
        // (lihat ApprovalService.NO_APPROVER) dan tidak ada jalan keluar dari dalam aplikasi.
        //
        // Pemisahan wewenangnya tidak ikut hilang: menambah maupun mengubah user di sini bukan
        // aksi langsung melainkan pengajuan, yang tetap harus diotorisasi lewat Admin Approval,
        // dan ApprovalService tidak pernah mengizinkan seseorang menyetujui pengajuan group-nya
        // sendiri. Jadi tidak ada satu orang pun yang bisa mengangkat approver seorang diri.
        List<GroupUser> assignable = allGroups;
        // BUG_AUSY_00110: kolom Group dulu menampilkan users.level (kolom lama yang tidak ikut
        // terisi saat group baru dipasang) sehingga group baru tampil kosong. Ambil dari master.
        Map<Integer,String> groupNames = new HashMap<>();
        for (GroupUser g : allGroups) groupNames.put(g.getIdGroup(), g.getNamaGroup());
        model.addAttribute("groups", assignable);       // untuk dropdown Tambah/Ubah user
        model.addAttribute("groupNames", groupNames);
        Set<String> resettable = new HashSet<>();     // hanya user sisi operasional (BUG_AUSY_00129)
        Set<String> protectedIds = new HashSet<>();   // akun utama sistem (BUG_AUSY_00131)
        for (User u : users.findAll()) {
            if (configChange.resettableByAdmin(u)) resettable.add(u.getUserId());
            if (u.isProtectedAccount()) protectedIds.add(u.getUserId());
        }
        model.addAttribute("resettable", resettable);
        model.addAttribute("protectedUsers", protectedIds);
        // Sisa masa berlaku password per user (REQ_AUSY_00009). Dihitung di sini, bukan di
        // Thymeleaf, supaya aturannya hanya ada di satu tempat.
        Map<String,String> masaLabel = new HashMap<>(), masaWarna = new HashMap<>();
        for (User u : users.findAll()) {
            masaLabel.put(u.getUserId(), passwordPolicy.label(u));
            masaWarna.put(u.getUserId(), passwordPolicy.warna(u));
        }
        model.addAttribute("masaLabel", masaLabel);
        model.addAttribute("masaWarna", masaWarna);
        model.addAttribute("peringatanPassword", passwordPolicy.daftarPeringatan());
        model.addAttribute("masaAktifHari", PasswordPolicyService.MASA_AKTIF_HARI);
        model.addAttribute("pendingConfig", configChange.pendingListByMakerGroup(me == null ? null : me.getIdGroup()).size());
        model.addAttribute("pendingIds", configChange.pendingIds("USER"));
        return "users/list";
    }

    // Add/Edit/Delete USER no longer apply directly — they require a reason and go to
    // Admin Approval (②) for authorization first (config dual control).
    @PostMapping("/add")
    public String add(@RequestParam String username, @RequestParam String password,
                      @RequestParam(required=false) String nama, @RequestParam(required=false) String email,
                      @RequestParam Integer idGroup, @RequestParam(required=false) String note,
                      @AuthenticationPrincipal AusyUserDetails me, RedirectAttributes ra) {
        ra.addFlashAttribute("ok", configChange.submitAdd(username, password, nama, email, idGroup, me, note));
        return "redirect:/users";
    }

    @PostMapping("/edit")
    public String edit(@RequestParam String userId, @RequestParam(required=false) String nama,
                       @RequestParam(required=false) String email, @RequestParam Integer idGroup,
                       @RequestParam Short status, @RequestParam(required=false) String password,
                       @RequestParam(required=false) String note,
                       @AuthenticationPrincipal AusyUserDetails me, RedirectAttributes ra) {
        ra.addFlashAttribute("ok", configChange.submitEdit(userId, nama, email, idGroup, status, password, me, note));
        return "redirect:/users";
    }

    /** BUG_AUSY_00129 — reset password user Maker/Checker, tetap lewat Admin Approval. */
    @PostMapping("/reset-password")
    public String resetPassword(@RequestParam String userId, @RequestParam String password,
                                @RequestParam(required=false) String note,
                                @AuthenticationPrincipal AusyUserDetails me, RedirectAttributes ra) {
        ra.addFlashAttribute("ok", configChange.submitPasswordReset(userId, password, me, note));
        return "redirect:/users";
    }

    @PostMapping("/delete")
    public String delete(@RequestParam String userId, @RequestParam(required=false) String note,
                         @AuthenticationPrincipal AusyUserDetails me, RedirectAttributes ra) {
        ra.addFlashAttribute("ok", configChange.submitDelete(userId, me, note));
        return "redirect:/users";
    }
}
