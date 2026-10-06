package com.mnc.ausy.service;

import com.mnc.ausy.domain.GroupUser;
import com.mnc.ausy.domain.User;
import com.mnc.ausy.repo.GroupUserRepository;
import com.mnc.ausy.repo.TlogActivityRepository;
import com.mnc.ausy.repo.TlogTransactionRepository;
import com.mnc.ausy.repo.UserRepository;
import com.mnc.ausy.security.AusyUserDetails;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.Pageable;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Siapa melihat jejak siapa di Audit Trail Admin — REQ_AUSY_00007 &amp; REQ_AUSY_00008.
 *
 * Diuji di sini, bukan lewat layar, karena di kelas inilah keputusannya diambil: salah sedikit
 * berarti jejak admin lain bocor, dan itu justru hal yang dilarang requirement-nya.
 */
class AuditConfigScopeTest {

    private static final int G_ADMIN = 1;        // CONFIG / SETTING
    private static final int G_APPROVER = 2;     // CONFIG APPROVER

    private UserRepository users;
    private PairingService pairing;
    private TlogTransactionRepository tx;
    private TlogActivityRepository act;
    private ApprovalService approval;
    private AuditScopeService scope;

    @BeforeEach
    void setUp() {
        users = mock(UserRepository.class);
        pairing = mock(PairingService.class);
        tx = mock(TlogTransactionRepository.class);
        act = mock(TlogActivityRepository.class);
        approval = mock(ApprovalService.class);
        GroupUserRepository groups = mock(GroupUserRepository.class);
        AccessService access = mock(AccessService.class);

        // Hanya role penyetuju config yang memegang menu Otorisasi Config.
        when(approval.configAuthorizer(G_APPROVER)).thenReturn(true);
        when(approval.configAuthorizer(G_ADMIN)).thenReturn(false);
        when(groups.findAll()).thenReturn(List.of(group(G_ADMIN), group(G_APPROVER)));
        when(access.sideOf(anyInt())).thenReturn("CONFIG");

        scope = new AuditScopeService(groups, access, approval, users, pairing, tx, act);
    }

    private static GroupUser group(int id) {
        GroupUser g = new GroupUser();
        g.setIdGroup(id);
        return g;
    }

    /** Daftarkan seorang user dan kembalikan principal-nya. */
    private AusyUserDetails daftar(String id, int idGroup, boolean akunUtama) {
        User u = new User();
        u.setUserId(id);
        u.setIdGroup(idGroup);
        u.setStatusAccount((short) 1);
        u.setProtectedAccount((short) (akunUtama ? 1 : 0));
        when(users.findByUserId(id)).thenReturn(Optional.of(u));
        when(pairing.isConfigApprover(u)).thenReturn(idGroup == G_APPROVER);
        when(pairing.isConfigMaker(u)).thenReturn(idGroup == G_ADMIN);
        return new AusyUserDetails(u);
    }

    // ---- REQ_AUSY_00007: akun pengawas melihat seluruhnya ----

    @Test
    void akunUtamaPenyetujuConfigMelihatSeluruhnya() {
        AusyUserDetails su = daftar("mncsuperadmin", G_APPROVER, true);

        scope.configTransactions(su, 100);

        verify(tx).findByUserGroups(any(), any(Pageable.class));
        verify(tx, never()).findByUserIds(any(), any(Pageable.class));
        verify(tx, never()).configVisibleToAdmin(anyString(), anyString(), any(Pageable.class));
        assertFalse(scope.configDibatasi(su));
    }

    /**
     * Admin Approval yang dibuat belakangan BUKAN Akun Utama, jadi ia tetap dibatasi — kalau
     * tidak, membuat satu user baru saja sudah cukup untuk melihat seluruh jejak.
     */
    @Test
    void adminApprovalBaruTidakIkutMenjadiPengawas() {
        AusyUserDetails baru = daftar("apv2", G_APPROVER, false);
        when(pairing.makersOf("apv2")).thenReturn(new LinkedHashSet<>(List.of("admin2")));

        scope.configTransactions(baru, 100);

        verify(tx, never()).findByUserGroups(any(), any(Pageable.class));
        assertTrue(scope.configDibatasi(baru));
    }

    /** Akun Utama sisi admin tetap bukan pengawas — ia diotorisasi, bukan mengotorisasi. */
    @Test
    void akunUtamaSisiAdminBukanPengawas() {
        AusyUserDetails adm = daftar("mncadmin", G_ADMIN, true);
        assertTrue(scope.configDibatasi(adm));
    }

    // ---- REQ_AUSY_00008: sisanya dibatasi pasangan ----

    @Test
    void adminApprovalMelihatDirinyaDanAdminPasangannya() {
        AusyUserDetails apv = daftar("apv2", G_APPROVER, false);
        when(pairing.makersOf("apv2")).thenReturn(new LinkedHashSet<>(List.of("admin2", "admin3")));

        scope.configTransactions(apv, 100);

        ArgumentCaptor<Collection<String>> cap = ArgumentCaptor.forClass(Collection.class);
        verify(tx).findByUserIds(cap.capture(), any(Pageable.class));
        assertEquals(Set.of("apv2", "admin2", "admin3"), new HashSet<>(cap.getValue()));
    }

    /**
     * Admin melihat jejaknya sendiri + keputusan atas PENGAJUANNYA, bukan seluruh log
     * pasangannya: satu Admin Approval memegang banyak admin, jadi log utuhnya memuat pengajuan
     * admin lain.
     */
    @Test
    void adminMelihatDirinyaDanKeputusanAtasPengajuannyaSaja() {
        AusyUserDetails adm = daftar("admin2", G_ADMIN, false);
        when(pairing.checkersOf("admin2")).thenReturn(new LinkedHashSet<>(List.of("apv2")));

        scope.configTransactions(adm, 100);

        verify(tx).configVisibleToAdmin(eq("admin2"), eq("%(maker=admin2)%"), any(Pageable.class));
        verify(tx, never()).findByUserGroups(any(), any(Pageable.class));
        verify(tx, never()).findByUserIds(any(), any(Pageable.class));
    }

    /** Penanda "(maker=x)" dikurung penuh supaya "admin" tidak ikut mencocokkan "admin2". */
    @Test
    void penandaMakerTidakMencocokkanAwalanNamaLain() {
        AusyUserDetails adm = daftar("admin", G_ADMIN, false);

        scope.configTransactions(adm, 100);

        ArgumentCaptor<String> like = ArgumentCaptor.forClass(String.class);
        verify(tx).configVisibleToAdmin(eq("admin"), like.capture(), any(Pageable.class));
        assertEquals("%(maker=admin)%", like.getValue());

        // Pola LIKE yang diapit % dan tanpa wildcard di tengah sama artinya dengan "memuat
        // potongan ini apa adanya" — jadi itulah yang diperiksa. (Menerjemahkannya ke regex
        // justru menyesatkan: tanda kurungnya berubah makna menjadi grup.)
        String inti = like.getValue().substring(1, like.getValue().length() - 1);
        assertEquals("(maker=admin)", inti);
        assertFalse("CONFIG_CHANGE_APPROVE Ubah User CR#9 (maker=admin2) di-approve".contains(inti),
                "pengajuan admin2 tidak boleh ikut terbawa ke layar admin");
        assertTrue("CONFIG_CHANGE_APPROVE Ubah User CR#8 (maker=admin) di-approve".contains(inti),
                "pengajuan admin sendiri harus tetap terbawa");
    }

    /** Login approver bukan "otorisasi atas pengajuan saya", jadi admin hanya melihat login sendiri. */
    @Test
    void adminHanyaMelihatLoginnyaSendiri() {
        AusyUserDetails adm = daftar("admin2", G_ADMIN, false);
        when(pairing.checkersOf("admin2")).thenReturn(new LinkedHashSet<>(List.of("apv2")));

        scope.configActivities(adm, 100);

        ArgumentCaptor<Collection<String>> cap = ArgumentCaptor.forClass(Collection.class);
        verify(act).findByUserIds(cap.capture(), any(Pageable.class));
        assertEquals(Set.of("admin2"), new HashSet<>(cap.getValue()));
    }

    /** Admin tanpa pasangan tetap melihat jejaknya sendiri, bukan layar kosong. */
    @Test
    void adminTanpaPasanganTetapMelihatJejaknyaSendiri() {
        AusyUserDetails adm = daftar("admin9", G_ADMIN, false);
        when(pairing.checkersOf("admin9")).thenReturn(new LinkedHashSet<>());

        scope.configTransactions(adm, 100);

        verify(tx).configVisibleToAdmin(eq("admin9"), eq("%(maker=admin9)%"), any(Pageable.class));
    }

    /** Principal tak dikenal tidak boleh jatuh ke "tanpa filter". */
    @Test
    void userTidakDikenalTidakMelihatApaPun() {
        User hantu = new User();
        hantu.setUserId("hantu");
        hantu.setIdGroup(G_ADMIN);
        when(users.findByUserId("hantu")).thenReturn(Optional.empty());

        assertTrue(scope.configTransactions(new AusyUserDetails(hantu), 100).isEmpty());
        assertTrue(scope.configActivities(new AusyUserDetails(hantu), 100).isEmpty());
        verifyNoInteractions(tx);
        verifyNoInteractions(act);
    }
}
