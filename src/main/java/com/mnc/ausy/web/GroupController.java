package com.mnc.ausy.web;

import com.mnc.ausy.repo.GroupUserRepository;
import com.mnc.ausy.security.AusyUserDetails;
import com.mnc.ausy.service.ConfigChangeService;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
@RequestMapping("/groups")
public class GroupController {
    private final GroupUserRepository groups;
    private final ConfigChangeService configChange;
    public GroupController(GroupUserRepository groups, ConfigChangeService configChange) {
        this.groups = groups; this.configChange = configChange;
    }

    @GetMapping
    public String list(Model model) {
        model.addAttribute("groups", groups.findAllByOrderByNamaGroupAsc());
        model.addAttribute("pendingConfig", configChange.countPending());
        model.addAttribute("pendingIds", configChange.pendingIds("GROUP"));
        model.addAttribute("inUse", configChange.groupsInUse());   // BUG_AUSY_00111
        return "groups/list";
    }

    // Tambah & Hapus role tidak ada dalam requirement — tombol dan endpoint-nya dihapus
    // (BUG_AUSY_00118). Yang tersisa hanya Ubah, tetap lewat approval Admin Approval.
    @PostMapping("/edit")
    public String edit(@RequestParam Integer idGroup, @RequestParam String namaGroup,
                       @RequestParam(required=false) String inisial, @RequestParam(required=false) String level,
                       @RequestParam(required=false) String note,
                       @AuthenticationPrincipal AusyUserDetails me, RedirectAttributes ra) {
        ra.addFlashAttribute("ok", configChange.submitGroupEdit(idGroup, namaGroup, inisial, level, me, note));
        return "redirect:/groups";
    }
}
