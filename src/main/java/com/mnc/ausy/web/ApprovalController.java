package com.mnc.ausy.web;

import com.mnc.ausy.domain.ApprovalGroup;
import com.mnc.ausy.domain.GroupUser;
import com.mnc.ausy.repo.ApprovalGroupRepository;
import com.mnc.ausy.repo.GroupUserRepository;
import com.mnc.ausy.security.AusyUserDetails;
import com.mnc.ausy.service.ConfigChangeService;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.*;

@Controller
@RequestMapping("/approval")
public class ApprovalController {
    private final ApprovalGroupRepository rules;
    private final GroupUserRepository groups;
    private final ConfigChangeService configChange;

    public ApprovalController(ApprovalGroupRepository rules, GroupUserRepository groups, ConfigChangeService configChange) {
        this.rules = rules; this.groups = groups; this.configChange = configChange;
    }

    @GetMapping
    public String list(Model model, @AuthenticationPrincipal AusyUserDetails me) {
        Map<Integer,String> name = new HashMap<>();
        for (GroupUser g : groups.findAll()) name.put(g.getIdGroup(), g.getNamaGroup());
        List<ApprovalGroup> rows = rules.findAll();
        for (ApprovalGroup r : rows) {
            r.setApproverName(name.getOrDefault(r.getApproverGroup(), "?"));
            r.setTargetName(name.getOrDefault(r.getTargetGroup(), "?"));
        }
        model.addAttribute("rules", rows);
        model.addAttribute("groups", groups.findAllByOrderByNamaGroupAsc());
        model.addAttribute("pendingConfig", configChange.pendingListByMakerGroup(me == null ? null : me.getIdGroup()).size());
        model.addAttribute("pendingIds", configChange.pendingIds("APPROVAL"));
        return "approval/list";
    }

    // Tambah/Hapus aturan approval -> config dual control (butuh alasan + approval Admin Approval).
    @PostMapping("/add")
    public String add(@RequestParam Integer approverGroup, @RequestParam Integer targetGroup,
                      @RequestParam(required=false) String note,
                      @AuthenticationPrincipal AusyUserDetails me, RedirectAttributes ra) {
        ra.addFlashAttribute("ok", configChange.submitApprovalAdd(approverGroup, targetGroup, me, note));
        return "redirect:/approval";
    }

    @PostMapping("/delete")
    public String delete(@RequestParam Long id, @RequestParam(required=false) String note,
                         @AuthenticationPrincipal AusyUserDetails me, RedirectAttributes ra) {
        ra.addFlashAttribute("ok", configChange.submitApprovalDelete(id, me, note));
        return "redirect:/approval";
    }
}
