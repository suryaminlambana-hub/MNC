package com.mnc.ausy.web;

import com.mnc.ausy.domain.User;
import com.mnc.ausy.repo.GroupUserRepository;
import com.mnc.ausy.security.AusyUserDetails;
import com.mnc.ausy.service.ConfigChangeService;
import com.mnc.ausy.service.PairingService;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.*;

/**
 * Pasangan Admin - Admin Approval (REQ_AUSY_00006).
 *
 * Sejajar dengan Pasangan Checker - Maker, tapi untuk sisi administratif. Yang ditentukan
 * pasangan ini BUKAN wewenang otorisasi — itu tetap matriks Approval Group — melainkan siapa
 * melihat jejak siapa di Audit Trail Admin (REQ_AUSY_00008): seorang Admin Approval melihat
 * jejak admin yang dipegangnya, dan seorang admin melihat keputusan atas pengajuannya sendiri.
 *
 * Seperti perubahan config lainnya: butuh alasan dan menunggu Admin Approval, jadi tidak ada
 * admin yang bisa memilih sendiri siapa yang mengawasi jejaknya.
 */
@Controller
@RequestMapping("/pairing-admin")
public class PairingAdminController {
    private final PairingService pairing;
    private final ConfigChangeService configChange;
    private final GroupUserRepository groups;

    public PairingAdminController(PairingService pairing, ConfigChangeService configChange,
                                  GroupUserRepository groups) {
        this.pairing = pairing; this.configChange = configChange; this.groups = groups;
    }

    /** Satu baris ringkasan: seorang Admin Approval beserta admin yang dipegangnya. */
    public record Row(String approver, String nama, List<String> admins) {}

    @GetMapping
    public String index(@RequestParam(required=false) String approver, Model model,
                        @AuthenticationPrincipal AusyUserDetails me) {
        Map<Integer,String> roleName = new HashMap<>();
        for (var g : groups.findAllByOrderByNamaGroupAsc()) roleName.put(g.getIdGroup(), g.getNamaGroup());

        List<User> approvers = pairing.configApproverUsers();
        List<Row> rows = new ArrayList<>();
        for (User a : approvers)
            rows.add(new Row(a.getUserId(), a.getNama(), new ArrayList<>(pairing.makersOf(a.getUserId()))));

        model.addAttribute("rows", rows);
        model.addAttribute("approvers", approvers);
        model.addAttribute("admins", pairing.configMakerUsers());
        model.addAttribute("roleName", roleName);
        model.addAttribute("unpairedAdmins", pairing.unpairedConfigMakers());
        model.addAttribute("selectedApprover", approver);
        model.addAttribute("pendingIds", configChange.pendingIds("PAIRING_ADM"));
        model.addAttribute("pendingConfig", configChange.pendingListByMakerGroup(me == null ? null : me.getIdGroup()).size());
        if (approver != null && !approver.isBlank())
            model.addAttribute("assigned", pairing.makersOf(approver));
        return "pairing-admin/index";
    }

    @PostMapping("/save")
    public String save(@RequestParam String approver,
                       @RequestParam(name="admin", required=false) List<String> admins,
                       @RequestParam(required=false) String note,
                       @AuthenticationPrincipal AusyUserDetails me, RedirectAttributes ra) {
        ra.addFlashAttribute("ok", configChange.submitPairingAdmin(approver, admins, me, note));
        return "redirect:/pairing-admin?approver=" + approver;
    }
}
