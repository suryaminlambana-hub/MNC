package com.mnc.ausy.web;

import com.mnc.ausy.domain.Menu;
import com.mnc.ausy.repo.GroupMenuRepository;
import com.mnc.ausy.repo.MenuRepository;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.*;

@Controller
@RequestMapping("/menus")
public class MenuAdminController {
    private final MenuRepository menus;
    private final GroupMenuRepository groupMenus;

    public MenuAdminController(MenuRepository menus, GroupMenuRepository groupMenus) {
        this.menus = menus; this.groupMenus = groupMenus;
    }

    @GetMapping
    public String list(Model model) {
        List<Menu> all = menus.findAllByOrderByParentAscUrutanAsc();
        Map<Integer,String> parentName = new HashMap<>();
        for (Menu m : all) if (m.getParent()==0) parentName.put(m.getIdMenu(), m.getNamaMenu());
        model.addAttribute("menus", all);
        model.addAttribute("parentName", parentName);
        model.addAttribute("parents", all.stream().filter(m -> m.getParent()==0).toList());
        return "menus/list";
    }

    @PostMapping("/add")
    public String add(@RequestParam String namaMenu, @RequestParam(required=false) String url,
                      @RequestParam Integer parent, @RequestParam(defaultValue="0") Integer urutan, RedirectAttributes ra) {
        Menu m = new Menu();
        m.setNamaMenu(namaMenu); m.setUrl(url==null||url.isBlank()?null:url.trim());
        m.setParent(parent); m.setUrutan(urutan); m.setActive((short)1);
        menus.save(m);
        ra.addFlashAttribute("ok", "Menu ditambahkan.");
        return "redirect:/menus";
    }

    @PostMapping("/edit")
    public String edit(@RequestParam Integer idMenu, @RequestParam String namaMenu, @RequestParam(required=false) String url,
                       @RequestParam Integer parent, @RequestParam(defaultValue="0") Integer urutan, RedirectAttributes ra) {
        menus.findById(idMenu).ifPresent(m -> {
            m.setNamaMenu(namaMenu); m.setUrl(url==null||url.isBlank()?null:url.trim());
            m.setParent(parent); m.setUrutan(urutan); menus.save(m);
        });
        ra.addFlashAttribute("ok", "Menu diperbarui.");
        return "redirect:/menus";
    }

    @PostMapping("/delete")
    public String delete(@RequestParam Integer idMenu, RedirectAttributes ra) {
        groupMenus.deleteByIdMenu(idMenu);
        menus.deleteById(idMenu);
        ra.addFlashAttribute("ok", "Menu dihapus.");
        return "redirect:/menus";
    }
}
