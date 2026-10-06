package com.mnc.ausy.security;

import com.mnc.ausy.domain.User;
import com.mnc.ausy.service.PasswordPolicyService;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.util.Collection;
import java.util.List;

/** Wraps a {@link User}, exposing group id + display name for the UI. */
public class AusyUserDetails implements UserDetails {

    private final User user;

    public AusyUserDetails(User user) { this.user = user; }

    public Integer getIdGroup() { return user.getIdGroup(); }
    public String getNama() { return user.getNama(); }
    public String getLevel() { return user.getLevel(); }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        String lvl = user.getLevel() == null ? "USER" : user.getLevel().toUpperCase();
        return List.of(
            new SimpleGrantedAuthority("ROLE_" + lvl),
            new SimpleGrantedAuthority("GROUP_" + user.getIdGroup())
        );
    }

    @Override public String getPassword() { return user.getPass(); }
    @Override public String getUsername() { return user.getUserId(); }
    @Override public boolean isAccountNonExpired() { return true; }
    @Override public boolean isAccountNonLocked() { return true; }
    /**
     * Password yang lewat masa berlakunya menolak login (BUG_AUSY_00190).
     *
     * Diperiksa Spring Security SETELAH passwordnya cocok, jadi pesan "password kedaluwarsa"
     * hanya muncul bagi orang yang memang tahu passwordnya — salah password tetap berbunyi
     * salah password, tidak membocorkan keadaan akun kepada yang menebak-nebak.
     *
     * Hitungannya dipinjam utuh dari {@link PasswordPolicyService}, bukan ditulis ulang di sini:
     * pintu masuk dan layar peringatan harus memakai aturan yang sama, kalau tidak layar akan
     * menjanjikan sesuatu yang tidak ditepati pintunya. Akun Utama dikecualikan di sana
     * (REQ_AUSY_00010).
     */
    @Override public boolean isCredentialsNonExpired() {
        return !PasswordPolicyService.sudahLewat(user);
    }
    @Override public boolean isEnabled() { return user.getStatusAccount() != null && user.getStatusAccount() == 1; }
}
