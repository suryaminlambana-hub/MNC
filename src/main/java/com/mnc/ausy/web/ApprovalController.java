package com.mnc.ausy.web;

import com.mnc.ausy.domain.ApprovalGroup;
import com.mnc.ausy.domain.GroupUser;
import com.mnc.ausy.repo.ApprovalGroupRepository;
import com.mnc.ausy.repo.GroupUserRepository;
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

    public ApprovalController(ApprovalGroupRepository rules, GroupUserRepository groups) {
        this.rules = rules; this.groups = groups;
    }

    @GetMapping
    public String list(Model model) {
        Map<Integer,String> name = new HashMap<>();
        for (GroupUser g : groups.findAll()) name.put(g.getIdGroup(), g.getNamaGroup());
        List<ApprovalGroup> rows = rules.findAll();
        for (ApprovalGroup r : rows) {
            r.setApproverName(name.getOrDefault(r.getApproverGroup(), "?"));
            r.setTargetName(name.getOrDefault(r.getTargetGroup(), "?"));
        }
        model.addAttribute("rules", rows);
        model.addAttribute("groups", groups.findAllByOrderByNamaGroupAsc());
        return "approval/list";
    }

    @PostMapping("/add")
    public String add(@RequestParam Integer approverGroup, @RequestParam Integer targetGroup, RedirectAttributes ra) {
        if (approverGroup.equals(targetGroup)) { ra.addFlashAttribute("warn", "Approver dan target tidak boleh group yang sama."); }
        else if (rules.existsByApproverGroupAndTargetGroup(approverGroup, targetGroup)) { ra.addFlashAttribute("warn", "Aturan tersebut sudah ada."); }
        else {
            ApprovalGroup r = new ApprovalGroup(); r.setApproverGroup(approverGroup); r.setTargetGroup(targetGroup); rules.save(r);
            ra.addFlashAttribute("ok", "Aturan persetujuan ditambahkan.");
        }
        return "redirect:/approval";
    }

    @PostMapping("/delete")
    public String delete(@RequestParam Long id, RedirectAttributes ra) {
        rules.deleteById(id);
        ra.addFlashAttribute("ok", "Aturan persetujuan dihapus.");
        return "redirect:/approval";
    }
}
