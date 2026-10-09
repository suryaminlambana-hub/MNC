package com.mnc.ausy.web;

import com.mnc.ausy.domain.TlogActivity;
import com.mnc.ausy.domain.TlogTransaction;
import com.mnc.ausy.repo.TlogActivityRepository;
import com.mnc.ausy.repo.TlogTransactionRepository;
import com.mnc.ausy.security.AusyUserDetails;
import com.mnc.ausy.service.NamaUserService;
import com.mnc.ausy.service.AuditScopeService;
import com.mnc.ausy.service.DataScopeService;
import com.mnc.ausy.service.AuditService;
import com.mnc.ausy.service.MncPdfService;
import com.lowagie.text.Document;
import com.lowagie.text.Element;
import com.lowagie.text.PageSize;
import com.lowagie.text.Paragraph;
import com.lowagie.text.pdf.PdfPCell;
import com.lowagie.text.pdf.PdfPTable;
import com.lowagie.text.pdf.PdfWriter;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Set;

/** Immutable audit-trail viewer (read-only) — PDF §7.3 / §8 / §9.
 *  Isinya terpisah per sisi kerja: yang memegang menu administratif hanya melihat jejak
 *  Admin & Admin Approval, yang memegang menu operasional hanya melihat jejak Maker & Checker.
 *  Sisi itu dibaca dari hak akses group (AccessService.sideOf), bukan dari id group yang
 *  ditulis di kode, supaya group buatan admin ikut tersaring dengan benar (BUG_AUSY_00115). */
@Controller
public class AuditController {
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("dd-MM-yyyy HH:mm:ss");
    private final NamaUserService nama;
    private final TlogTransactionRepository tx;
    private final TlogActivityRepository act;
    private final AuditScopeService scope;
    private final DataScopeService dataScope;
    private final MncPdfService mnc;
    private final AuditService audit;

    public AuditController(TlogTransactionRepository tx, TlogActivityRepository act,
                           AuditScopeService scope, DataScopeService dataScope,
                           MncPdfService mnc, AuditService audit, NamaUserService nama) {
        this.dataScope = dataScope; this.nama = nama;
        this.tx = tx; this.act = act; this.scope = scope;
        this.mnc = mnc; this.audit = audit;
    }

    /** Group mana saja yang boleh dilihat jejaknya oleh viewer ini — selalu tersaring per sisi. */
    /* Dua halaman terpisah, masing-masing hak aksesnya sendiri di Access Menu:
       /audit       -> jejak Maker & Checker      (bagian Checker)
       /audit-admin -> jejak Admin & Admin Approval (bagian Admin Approval)
       Isinya ditentukan halaman yang dibuka, bukan ditebak dari group pembacanya. */

    @GetMapping("/audit")
    public String auditBusiness(Model model, @AuthenticationPrincipal AusyUserDetails me) {
        return render(AuditScopeService.BUSINESS, model, me);
    }

    @GetMapping("/audit-admin")
    public String auditConfig(Model model, @AuthenticationPrincipal AusyUserDetails me) {
        return render(AuditScopeService.CONFIG, model, me);
    }

    @GetMapping("/audit/export")
    public void exportBusiness(HttpServletResponse resp, @AuthenticationPrincipal AusyUserDetails me) throws Exception {
        export(AuditScopeService.BUSINESS, resp, me);
    }

    @GetMapping("/audit-admin/export")
    public void exportConfig(HttpServletResponse resp, @AuthenticationPrincipal AusyUserDetails me) throws Exception {
        export(AuditScopeService.CONFIG, resp, me);
    }

    private String render(String side, Model model, AusyUserDetails me) {
        // Sisi operasional: isinya mengikuti pasangan Checker - Maker, jadi checker tidak melihat
        // aktivitas maker di luar naungannya (BUG_AUSY_00149).
        Set<String> users = AuditScopeService.BUSINESS.equals(side) ? dataScope.visibleMakers(me) : null;
        if (users != null) {
            if (users.isEmpty()) users = Set.of("\u0000");     // tidak ada pasangan: kosongkan
            model.addAttribute("transactions", tx.findByUserIds(users, PageRequest.of(0, 100)));
            model.addAttribute("activities", act.findByUserIds(users, PageRequest.of(0, 100)));
        } else {
            List<Integer> groups = scope.groupsVisibleTo(me == null ? null : me.getIdGroup(), side);
            model.addAttribute("transactions", tx.findByUserGroups(groups, PageRequest.of(0, 100)));
            model.addAttribute("activities", act.findByUserGroups(groups, PageRequest.of(0, 100)));
        }
        model.addAttribute("scoped", true);
        model.addAttribute("scopeLabel", scope.label(side));
        model.addAttribute("exportUrl", AuditScopeService.CONFIG.equals(side) ? "/audit-admin/export" : "/audit/export");
        return "audit/index";
    }

    /**
     * Unduh audit trail sebagai PDF (BUG_AUSY_00152).
     *
     * Dulu Excel. Audit trail dipakai sebagai bukti untuk auditor, dan berkas Excel bisa
     * disunting siapa saja yang memegangnya tanpa meninggalkan jejak — PDF jauh lebih sulit
     * diubah diam-diam dan itu yang diminta. Formatnya mengikuti cetakan MNC yang lain:
     * kertas mendatar, logo, tiga baris judul.
     */
    private void export(String side, HttpServletResponse resp, AusyUserDetails me) throws Exception {
        Set<String> users = AuditScopeService.BUSINESS.equals(side) ? dataScope.visibleMakers(me) : null;
        if (users != null && users.isEmpty()) users = Set.of("\u0000");
        List<Integer> groups = users != null ? List.of()
                : scope.groupsVisibleTo(me == null ? null : me.getIdGroup(), side);

        List<TlogTransaction> txRows = users != null ? tx.findByUserIds(users, PageRequest.of(0, 5000))
                                                    : tx.findByUserGroups(groups, PageRequest.of(0, 5000));
        List<TlogActivity> actRows = users != null ? act.findByUserIds(users, PageRequest.of(0, 5000))
                                                   : act.findByUserGroups(groups, PageRequest.of(0, 5000));

        boolean config = AuditScopeService.CONFIG.equals(side);
        String name = config ? "Audit_Trail_Admin" : "Audit_Trail_Maker_Checker";
        String judul = config ? "AUDIT TRAIL — ADMIN & ADMIN APPROVAL"
                              : "AUDIT TRAIL — MAKER & CHECKER";
        String stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));

        resp.setContentType("application/pdf");
        resp.setHeader("Content-Disposition", "attachment; filename=\"" + name + "_" + stamp + ".pdf\"");

        Document doc = new Document(PageSize.A4.rotate(), 24, 24, 24, 30);
        PdfWriter writer = PdfWriter.getInstance(doc, resp.getOutputStream());
        doc.open();
        mnc.stampLogo(writer, doc);
        mnc.title(doc, judul, "Dicetak " + LocalDateTime.now().format(PRINTED)
                + " oleh " + (me == null ? "-" : nama.of(me.getUsername())));

        // Dua bagian, judulnya sama dengan nama tab di layar (BUG_AUSY_00144) supaya yang
        // membaca cetakan ini tahu bagian mana yang sedang dilihatnya.
        doc.add(section("Aktivitas Terakhir"));
        PdfPTable t1 = table(new float[]{1.6f, 1.2f, 2f, 6f}, "Waktu", "User", "Aksi", "Detail");
        for (TlogTransaction t : txRows) {
            t1.addCell(mnc.cell(t.getTimeActivity() == null ? "" : t.getTimeActivity().format(TS)));
            t1.addCell(mnc.cell(nama.of(t.getUserId())));
            t1.addCell(mnc.cell(t.getNameActivity()));
            t1.addCell(mnc.cell(nama.teks(t.getTinfo())));
        }
        if (txRows.isEmpty()) t1.addCell(empty(4));
        doc.add(t1);

        doc.add(new Paragraph(" "));
        doc.add(section("Aktivitas / Login"));
        PdfPTable t2 = table(new float[]{1.6f, 1.2f, 1.6f, 2f, 5f}, "Waktu", "User", "IP", "Aktivitas", "Info");
        for (TlogActivity a : actRows) {
            t2.addCell(mnc.cell(a.getTimeActivity() == null ? "" : a.getTimeActivity().format(TS)));
            t2.addCell(mnc.cell(nama.of(a.getUserId())));
            t2.addCell(mnc.cell(a.getIp()));
            t2.addCell(mnc.cell(a.getNameActivity()));
            t2.addCell(mnc.cell(a.getInfo()));
        }
        if (actRows.isEmpty()) t2.addCell(empty(5));
        doc.add(t2);
        doc.close();

        if (me != null)
            audit.transaction(me.getUsername(), "EXPORT_AUDIT_PDF",
                    "Unduh " + judul + " sebagai PDF: " + txRows.size() + " transaksi, " + actRows.size() + " aktivitas");
    }

    private static final DateTimeFormatter PRINTED = DateTimeFormatter.ofPattern("dd-MM-yyyy HH:mm:ss");

    private PdfPTable table(float[] widths, String... heads) {
        PdfPTable t = new PdfPTable(widths);
        t.setWidthPercentage(100);
        t.setHeaderRows(1);
        for (String h : heads) t.addCell(mnc.head(h));
        return t;
    }

    private static Paragraph section(String title) {
        return new Paragraph(title, MncPdfService.FOOT_B);
    }

    private PdfPCell empty(int span) {
        PdfPCell c = mnc.cell("Tidak ada data.");
        c.setColspan(span);
        c.setHorizontalAlignment(Element.ALIGN_CENTER);
        return c;
    }
}
