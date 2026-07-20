package com.mnc.ausy.web;

import com.mnc.ausy.domain.GroupUser;
import com.mnc.ausy.repo.GroupUserRepository;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
@RequestMapping("/groups")
public class GroupController {
    private final GroupUserRepository groups;
    public GroupController(GroupUserRepository groups) { this.groups = groups; }

    @GetMapping
    public String list(Model model) {
        model.addAttribute("groups", groups.findAllByOrderByNamaGroupAsc());
        return "groups/list";
    }

    @PostMapping("/add")
    public String add(@RequestParam String namaGroup, @RequestParam(required=false) String inisial,
                      @RequestParam(required=false) String level, RedirectAttributes ra) {
        GroupUser g = new GroupUser();
        g.setNamaGroup(namaGroup); g.setInisial(inisial); g.setLevel(level);
        groups.save(g);
        ra.addFlashAttribute("ok", "Group berhasil ditambahkan.");
        return "redirect:/groups";
    }

    @PostMapping("/edit")
    public String edit(@RequestParam Integer idGroup, @RequestParam String namaGroup,
                       @RequestParam(required=false) String inisial, @RequestParam(required=false) String level, RedirectAttributes ra) {
        groups.findById(idGroup).ifPresent(g -> {
            g.setNamaGroup(namaGroup); g.setInisial(inisial); g.setLevel(level); groups.save(g);
        });
        ra.addFlashAttribute("ok", "Group berhasil diperbarui.");
        return "redirect:/groups";
    }

    @PostMapping("/delete")
    public String delete(@RequestParam Integer idGroup, RedirectAttributes ra) {
        groups.deleteById(idGroup);
        ra.addFlashAttribute("ok", "Group berhasil dihapus.");
        return "redirect:/groups";
    }
}
