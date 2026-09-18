package com.mnc.ausy.web;

import com.mnc.ausy.domain.User;
import com.mnc.ausy.repo.GroupUserRepository;
import com.mnc.ausy.repo.UserRepository;
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
 * Pasangan Checker - Maker (BUG_AUSY_00139).
 *
 * Admin menetapkan maker mana saja yang menjadi tanggung jawab seorang checker. Checker hanya
 * melihat dan bisa memutuskan pengajuan — serta melihat data GL — milik maker yang dipasangkan
 * padanya, sementara antar-maker tetap tidak saling melihat (BUG_AUSY_00138).
 *
 * Seperti perubahan Master Data lainnya: butuh alasan dan menunggu Admin Approval.
 */
@Controller
@RequestMapping("/pairing")
public class PairingController {
    private final PairingService pairing;
    private final ConfigChangeService configChange;
    private final UserRepository users;
    private final GroupUserRepository groups;

    public PairingController(PairingService pairing, ConfigChangeService configChange,
                             UserRepository users, GroupUserRepository groups) {
        this.pairing = pairing; this.configChange = configChange; this.users = users; this.groups = groups;
    }

    /** Satu baris ringkasan di tabel: seorang checker beserta maker yang dipegangnya. */
    public record Row(String checker, String nama, List<String> makers) {}

    @GetMapping
    public String index(@RequestParam(required=false) String checker, Model model,
                        @AuthenticationPrincipal AusyUserDetails me) {
        Map<Integer,String> roleName = new HashMap<>();
        for (var g : groups.findAllByOrderByNamaGroupAsc()) roleName.put(g.getIdGroup(), g.getNamaGroup());

        List<User> checkers = pairing.checkerUsers();
        List<Row> rows = new ArrayList<>();
        for (User c : checkers)
            rows.add(new Row(c.getUserId(), c.getNama(), new ArrayList<>(pairing.makersOf(c.getUserId()))));

        model.addAttribute("rows", rows);
        model.addAttribute("checkers", checkers);
        model.addAttribute("makers", pairing.makerUsers());
        model.addAttribute("roleName", roleName);
        model.addAttribute("unpairedMakers", pairing.unpairedMakers());
        model.addAttribute("selectedChecker", checker);
        model.addAttribute("pendingIds", configChange.pendingIds("PAIRING"));
        model.addAttribute("pendingConfig", configChange.pendingListByMakerGroup(me == null ? null : me.getIdGroup()).size());
        if (checker != null && !checker.isBlank())
            model.addAttribute("assigned", pairing.makersOf(checker));
        return "pairing/index";
    }

    @PostMapping("/save")
    public String save(@RequestParam String checker,
                       @RequestParam(name="maker", required=false) List<String> makers,
                       @RequestParam(required=false) String note,
                       @AuthenticationPrincipal AusyUserDetails me, RedirectAttributes ra) {
        ra.addFlashAttribute("ok", configChange.submitPairing(checker, makers, me, note));
        return "redirect:/pairing?checker=" + checker;
    }
}
