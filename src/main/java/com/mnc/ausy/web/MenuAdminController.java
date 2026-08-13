package com.mnc.ausy.web;

import com.mnc.ausy.domain.Menu;
import com.mnc.ausy.repo.MenuRepository;
import com.mnc.ausy.security.AusyUserDetails;
import com.mnc.ausy.service.ConfigChangeService;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.*;

@Controller
@RequestMapping("/menus")
public class MenuAdminController {
    private final MenuRepository menus;
    private final ConfigChangeService configChange;

    public MenuAdminController(MenuRepository menus, ConfigChangeService configChange) {
        this.menus = menus; this.configChange = configChange;
    }

    @GetMapping
    public String list(Model model) {
        List<Menu> all = menus.findAllByOrderByParentAscUrutanAsc();
        Map<Integer,String> parentName = new HashMap<>();
        for (Menu m : all) if (m.getParent()==0) parentName.put(m.getIdMenu(), m.getNamaMenu());
        model.addAttribute("menus", all);
        model.addAttribute("parentName", parentName);
        model.addAttribute("parents", all.stream().filter(m -> m.getParent()==0).toList());
        model.addAttribute("pendingConfig", configChange.countPending());
        return "menus/list";
    }

    // Add/Edit/Delete Menu -> config dual control (butuh alasan + approval Admin Approval).
    @PostMapping("/add")
    public String add(@RequestParam String namaMenu, @RequestParam(required=false) String url,
                      @RequestParam Integer parent, @RequestParam(defaultValue="0") Integer urutan,
                      @RequestParam(required=false) String note,
                      @AuthenticationPrincipal AusyUserDetails me, RedirectAttributes ra) {
        ra.addFlashAttribute("ok", configChange.submitMenuAdd(namaMenu, url, parent, urutan, me, note));
        return "redirect:/menus";
    }

    @PostMapping("/edit")
    public String edit(@RequestParam Integer idMenu, @RequestParam String namaMenu, @RequestParam(required=false) String url,
                       @RequestParam Integer parent, @RequestParam(defaultValue="0") Integer urutan,
                       @RequestParam(required=false) String note,
                       @AuthenticationPrincipal AusyUserDetails me, RedirectAttributes ra) {
        ra.addFlashAttribute("ok", configChange.submitMenuEdit(idMenu, namaMenu, url, parent, urutan, me, note));
        return "redirect:/menus";
    }

    @PostMapping("/delete")
    public String delete(@RequestParam Integer idMenu, @RequestParam(required=false) String note,
                         @AuthenticationPrincipal AusyUserDetails me, RedirectAttributes ra) {
        ra.addFlashAttribute("ok", configChange.submitMenuDelete(idMenu, me, note));
        return "redirect:/menus";
    }
}
