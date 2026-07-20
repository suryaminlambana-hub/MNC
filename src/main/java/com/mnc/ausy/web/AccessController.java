package com.mnc.ausy.web;

import com.mnc.ausy.domain.GroupMenu;
import com.mnc.ausy.domain.Menu;
import com.mnc.ausy.repo.*;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.*;

@Controller
@RequestMapping("/access")
public class AccessController {
    private final GroupUserRepository groups;
    private final MenuRepository menus;
    private final GroupMenuRepository groupMenus;

    public AccessController(GroupUserRepository groups, MenuRepository menus, GroupMenuRepository groupMenus) {
        this.groups = groups; this.menus = menus; this.groupMenus = groupMenus;
    }

    @GetMapping
    public String index(@RequestParam(required=false) Integer group, Model model) {
        model.addAttribute("groups", groups.findAllByOrderByNamaGroupAsc());
        model.addAttribute("selectedGroup", group);
        if (group != null) {
            Set<Integer> granted = new HashSet<>();
            for (GroupMenu gm : groupMenus.findByIdGroup(group)) granted.add(gm.getIdMenu());
            // parents with children, marking granted
            List<Menu> all = menus.findAllByOrderByParentAscUrutanAsc();
            List<Menu> parents = new ArrayList<>();
            Map<Integer,Menu> byId = new HashMap<>();
            for (Menu m : all) byId.put(m.getIdMenu(), m);
            for (Menu m : all) if (m.getParent()==0) { m.setChildren(new ArrayList<>()); parents.add(m); }
            for (Menu m : all) if (m.getParent()!=0 && byId.containsKey(m.getParent())) byId.get(m.getParent()).getChildren().add(m);
            model.addAttribute("parents", parents);
            model.addAttribute("granted", granted);
        }
        return "access/index";
    }

    @PostMapping("/save")
    public String save(@RequestParam Integer group,
                       @RequestParam(name="menu", required=false) List<Integer> menuIds,
                       RedirectAttributes ra) {
        Set<Integer> checked = menuIds == null ? Set.of() : new HashSet<>(menuIds);
        for (Menu m : menus.findAllByOrderByParentAscUrutanAsc()) {
            if (m.getParent() == 0) continue;            // only assign leaf menus
            boolean want = checked.contains(m.getIdMenu());
            boolean has  = groupMenus.existsByIdGroupAndIdMenu(group, m.getIdMenu());
            if (want && !has) { GroupMenu gm = new GroupMenu(); gm.setIdGroup(group); gm.setIdMenu(m.getIdMenu()); groupMenus.save(gm); }
            else if (!want && has) { groupMenus.deleteByIdGroupAndIdMenu(group, m.getIdMenu()); }
        }
        ra.addFlashAttribute("ok", "Hak akses menu berhasil disimpan.");
        return "redirect:/access?group=" + group;
    }
}
