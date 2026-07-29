package com.mnc.ausy.web;

import com.mnc.ausy.repo.GlTabelRepository;
import com.mnc.ausy.security.AusyUserDetails;
import com.mnc.ausy.service.GlChangeService;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.List;
import java.util.Map;

/** Workflow 8.3 — Update GL: maker page (/gl-data) + checker review (/gl-change/review). */
@Controller
public class GlChangeController {
    private final GlChangeService service;
    private final GlTabelRepository gl;

    public GlChangeController(GlChangeService service, GlTabelRepository gl) {
        this.service = service; this.gl = gl;
    }

    // ---- Maker ----
    @GetMapping("/gl-data")
    public String dataPage(@AuthenticationPrincipal AusyUserDetails me, Model model) {
        model.addAttribute("rows", gl.findTop200ByActiveOrderByIdDesc((short)1));
        model.addAttribute("fields", GlChangeService.FIELDS);
        return "gl-data/index";
    }

    @PostMapping("/gl-data/add")
    public String add(@RequestParam Map<String,String> form, @RequestParam(required=false) String note,
                      @AuthenticationPrincipal AusyUserDetails me, RedirectAttributes ra) {
        ra.addFlashAttribute("ok", service.submitAdd(form, me, note));
        return "redirect:/gl-data";
    }

    @PostMapping("/gl-data/edit")
    public String edit(@RequestParam Long glId, @RequestParam Map<String,String> form, @RequestParam(required=false) String note,
                       @AuthenticationPrincipal AusyUserDetails me, RedirectAttributes ra) {
        ra.addFlashAttribute("ok", service.submitEdit(glId, form, me, note));
        return "redirect:/gl-data";
    }

    @PostMapping("/gl-data/delete")
    public String delete(@RequestParam Long glId, @RequestParam(required=false) String note,
                         @AuthenticationPrincipal AusyUserDetails me, RedirectAttributes ra) {
        ra.addFlashAttribute("ok", service.submitDelete(glId, me, note));
        return "redirect:/gl-data";
    }

    @PostMapping("/gl-data/bulk-edit")
    public String bulkEdit(@RequestParam(required=false) List<Long> glIds, @RequestParam Map<String,String> form,
                           @RequestParam(required=false) String note,
                           @AuthenticationPrincipal AusyUserDetails me, RedirectAttributes ra) {
        ra.addFlashAttribute("ok", service.submitBulkEdit(glIds, form, me, note));
        return "redirect:/gl-data";
    }

    // ---- Checker ----
    @GetMapping("/gl-change/review")
    public String review(@AuthenticationPrincipal AusyUserDetails me, Model model) {
        model.addAttribute("items", service.pendingReviewFor(me.getIdGroup()));
        return "gl-change/review";
    }

    @PostMapping("/gl-change/approve")
    public String approve(@RequestParam Long crId, @RequestParam(required=false) String note,
                          @AuthenticationPrincipal AusyUserDetails me, RedirectAttributes ra) {
        ra.addFlashAttribute("ok", service.approve(crId, me, note));
        return "redirect:/gl-change/review";
    }

    @PostMapping("/gl-change/reject")
    public String reject(@RequestParam Long crId, @RequestParam(required=false) String note,
                         @AuthenticationPrincipal AusyUserDetails me, RedirectAttributes ra) {
        ra.addFlashAttribute("warn", service.reject(crId, me, note));
        return "redirect:/gl-change/review";
    }
}
