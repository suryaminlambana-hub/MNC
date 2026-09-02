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
        return "groups/list";
    }

    // Add/Edit/Delete Group -> config dual control (butuh alasan + approval Admin Approval).
    @PostMapping("/add")
    public String add(@RequestParam String namaGroup, @RequestParam(required=false) String inisial,
                      @RequestParam(required=false) String level, @RequestParam(required=false) String note,
                      @AuthenticationPrincipal AusyUserDetails me, RedirectAttributes ra) {
        ra.addFlashAttribute("ok", configChange.submitGroupAdd(namaGroup, inisial, level, me, note));
        return "redirect:/groups";
    }

    @PostMapping("/edit")
    public String edit(@RequestParam Integer idGroup, @RequestParam String namaGroup,
                       @RequestParam(required=false) String inisial, @RequestParam(required=false) String level,
                       @RequestParam(required=false) String note,
                       @AuthenticationPrincipal AusyUserDetails me, RedirectAttributes ra) {
        ra.addFlashAttribute("ok", configChange.submitGroupEdit(idGroup, namaGroup, inisial, level, me, note));
        return "redirect:/groups";
    }

    @PostMapping("/delete")
    public String delete(@RequestParam Integer idGroup, @RequestParam(required=false) String note,
                         @AuthenticationPrincipal AusyUserDetails me, RedirectAttributes ra) {
        ra.addFlashAttribute("ok", configChange.submitGroupDelete(idGroup, me, note));
        return "redirect:/groups";
    }
}
