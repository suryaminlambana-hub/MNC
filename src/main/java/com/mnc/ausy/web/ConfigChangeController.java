package com.mnc.ausy.web;

import com.mnc.ausy.security.AusyUserDetails;
import com.mnc.ausy.service.ConfigChangeService;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/** Admin Approval (②) authorizes Add/Edit/Delete USER requested by Admin (①). */
@Controller
public class ConfigChangeController {
    private final ConfigChangeService service;

    public ConfigChangeController(ConfigChangeService service) { this.service = service; }

    @GetMapping("/config-change/review")
    public String review(@AuthenticationPrincipal AusyUserDetails me, Model model) {
        var sections = service.pendingReviewSectionsFor(me.getIdGroup());
        long total = sections.stream().mapToLong(s -> s.items().size()).sum();
        model.addAttribute("sections", sections);
        model.addAttribute("total", total);
        return "config-change/review";
    }

    @PostMapping("/config-change/approve")
    public String approve(@RequestParam Long crId, @RequestParam(required=false) String note,
                          @AuthenticationPrincipal AusyUserDetails me, RedirectAttributes ra) {
        ra.addFlashAttribute("ok", service.approve(crId, me, note));
        return "redirect:/config-change/review";
    }

    @PostMapping("/config-change/reject")
    public String reject(@RequestParam Long crId, @RequestParam(required=false) String note,
                         @AuthenticationPrincipal AusyUserDetails me, RedirectAttributes ra) {
        ra.addFlashAttribute("ok", service.reject(crId, me, note));
        return "redirect:/config-change/review";
    }
}
