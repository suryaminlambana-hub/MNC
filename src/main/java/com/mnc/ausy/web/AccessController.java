package com.mnc.ausy.web;

import com.mnc.ausy.domain.GroupMenu;
import com.mnc.ausy.domain.Menu;
import com.mnc.ausy.repo.*;
import com.mnc.ausy.security.AusyUserDetails;
import com.mnc.ausy.service.ConfigChangeService;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
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
    private final ConfigChangeService configChange;

    public AccessController(GroupUserRepository groups, MenuRepository menus, GroupMenuRepository groupMenus,
                            ConfigChangeService configChange) {
        this.groups = groups; this.menus = menus; this.groupMenus = groupMenus; this.configChange = configChange;
    }

    @GetMapping
    public String index(@RequestParam(required=false) Integer group, Model model) {
        model.addAttribute("groups", groups.findAllByOrderByNamaGroupAsc());
        model.addAttribute("selectedGroup", group);
        model.addAttribute("pendingConfig", configChange.countPending());
        model.addAttribute("pendingIds", configChange.pendingIds("ACCESS"));
        if (group != null) {
            Set<Integer> granted = new HashSet<>();
            for (GroupMenu gm : groupMenus.findByIdGroup(group)) granted.add(gm.getIdMenu());
            // parents with children, marking granted
            // hanya item aktif — menu lama yang sudah dilebur tidak perlu muncul sebagai centangan
            List<Menu> all = menus.findAllByOrderByParentAscUrutanAsc().stream()
                    .filter(m -> m.getActive() != null && m.getActive() == 1).toList();
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

    // Simpan hak akses menu -> config dual control (butuh alasan + approval Admin Approval).
    @PostMapping("/save")
    public String save(@RequestParam Integer group,
                       @RequestParam(name="menu", required=false) List<Integer> menuIds,
                       @RequestParam(required=false) String note,
                       @AuthenticationPrincipal AusyUserDetails me, RedirectAttributes ra) {
        ra.addFlashAttribute("ok", configChange.submitAccess(group, menuIds, me, note));
        return "redirect:/access?group=" + group;
    }
}
