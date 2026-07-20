package com.mnc.ausy.web;

import com.mnc.ausy.repo.GroupUserRepository;
import com.mnc.ausy.repo.UserRepository;
import com.mnc.ausy.service.UserService;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import com.mnc.ausy.security.AusyUserDetails;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
@RequestMapping("/users")
public class UserController {
    private final UserRepository users;
    private final GroupUserRepository groups;
    private final UserService service;

    public UserController(UserRepository users, GroupUserRepository groups, UserService service) {
        this.users = users; this.groups = groups; this.service = service;
    }

    @GetMapping
    public String list(Model model) {
        model.addAttribute("users", users.findAll());
        model.addAttribute("groups", groups.findAllByOrderByNamaGroupAsc());
        return "users/list";
    }

    @PostMapping("/add")
    public String add(@RequestParam String username, @RequestParam String password,
                      @RequestParam(required=false) String nama, @RequestParam(required=false) String email,
                      @RequestParam Integer idGroup, @AuthenticationPrincipal AusyUserDetails me, RedirectAttributes ra) {
        boolean ok = service.add(username, password, nama, email, idGroup, me.getUsername());
        ra.addFlashAttribute(ok ? "ok" : "err", ok ? "User berhasil ditambahkan." : "Username sudah dipakai / tidak valid.");
        return "redirect:/users";
    }

    @PostMapping("/edit")
    public String edit(@RequestParam String userId, @RequestParam(required=false) String nama,
                       @RequestParam(required=false) String email, @RequestParam Integer idGroup,
                       @RequestParam Short status, @RequestParam(required=false) String password, RedirectAttributes ra) {
        service.edit(userId, nama, email, idGroup, status, password);
        ra.addFlashAttribute("ok", "User berhasil diperbarui.");
        return "redirect:/users";
    }

    @PostMapping("/delete")
    public String delete(@RequestParam String userId, RedirectAttributes ra) {
        service.delete(userId);
        ra.addFlashAttribute("ok", "User berhasil dihapus.");
        return "redirect:/users";
    }
}
