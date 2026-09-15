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
    public String list(Model model, @AuthenticationPrincipal AusyUserDetails me) {
        List<Menu> all = menus.findAllByOrderByParentAscUrutanAsc();
        Map<Integer,String> parentName = new HashMap<>();
        for (Menu m : all) if (m.getParent()==0) parentName.put(m.getIdMenu(), m.getNamaMenu());
        model.addAttribute("menus", all);
        model.addAttribute("parentName", parentName);
        model.addAttribute("parents", all.stream().filter(m -> m.getParent()==0).toList());
        model.addAttribute("pendingConfig", configChange.pendingListByMakerGroup(me == null ? null : me.getIdGroup()).size());
        model.addAttribute("pendingIds", configChange.pendingIds("MENU"));
        return "menus/list";
    }

    // Tambah & Hapus menu tidak ada dalam requirement — tombol dan endpoint-nya dihapus
    // (BUG_AUSY_00119). Yang tersisa hanya Ubah, tetap lewat approval Admin Approval.
    @PostMapping("/edit")
    public String edit(@RequestParam Integer idMenu, @RequestParam String namaMenu, @RequestParam(required=false) String url,
                       @RequestParam Integer parent, @RequestParam(defaultValue="0") Integer urutan,
                       @RequestParam(required=false) String note,
                       @AuthenticationPrincipal AusyUserDetails me, RedirectAttributes ra) {
        ra.addFlashAttribute("ok", configChange.submitMenuEdit(idMenu, namaMenu, url, parent, urutan, me, note));
        return "redirect:/menus";
    }
}
