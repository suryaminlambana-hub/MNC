package com.mnc.ausy.web;

import com.mnc.ausy.repo.GroupUserRepository;
import com.mnc.ausy.repo.UserRepository;
import com.mnc.ausy.security.AusyUserDetails;
import com.mnc.ausy.service.ConfigChangeService;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
@RequestMapping("/users")
public class UserController {
    private final UserRepository users;
    private final GroupUserRepository groups;
    private final ConfigChangeService configChange;

    public UserController(UserRepository users, GroupUserRepository groups, ConfigChangeService configChange) {
        this.users = users; this.groups = groups; this.configChange = configChange;
    }

    @GetMapping
    public String list(Model model) {
        model.addAttribute("users", users.findAll());
        model.addAttribute("groups", groups.findAllByOrderByNamaGroupAsc());
        model.addAttribute("pendingConfig", configChange.countPending());
        model.addAttribute("pendingIds", configChange.pendingIds("USER"));
        return "users/list";
    }

    // Add/Edit/Delete USER no longer apply directly — they require a reason and go to
    // Admin Approval (②) for authorization first (config dual control).
    @PostMapping("/add")
    public String add(@RequestParam String username, @RequestParam String password,
                      @RequestParam(required=false) String nama, @RequestParam(required=false) String email,
                      @RequestParam Integer idGroup, @RequestParam(required=false) String note,
                      @AuthenticationPrincipal AusyUserDetails me, RedirectAttributes ra) {
        ra.addFlashAttribute("ok", configChange.submitAdd(username, password, nama, email, idGroup, me, note));
        return "redirect:/users";
    }

    @PostMapping("/edit")
    public String edit(@RequestParam String userId, @RequestParam(required=false) String nama,
                       @RequestParam(required=false) String email, @RequestParam Integer idGroup,
                       @RequestParam Short status, @RequestParam(required=false) String password,
                       @RequestParam(required=false) String note,
                       @AuthenticationPrincipal AusyUserDetails me, RedirectAttributes ra) {
        ra.addFlashAttribute("ok", configChange.submitEdit(userId, nama, email, idGroup, status, password, me, note));
        return "redirect:/users";
    }

    @PostMapping("/delete")
    public String delete(@RequestParam String userId, @RequestParam(required=false) String note,
                         @AuthenticationPrincipal AusyUserDetails me, RedirectAttributes ra) {
        ra.addFlashAttribute("ok", configChange.submitDelete(userId, me, note));
        return "redirect:/users";
    }
}
