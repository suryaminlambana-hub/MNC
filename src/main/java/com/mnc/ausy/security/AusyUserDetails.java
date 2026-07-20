package com.mnc.ausy.security;

import com.mnc.ausy.domain.User;
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
    @Override public boolean isCredentialsNonExpired() { return true; }
    @Override public boolean isEnabled() { return user.getStatusAccount() != null && user.getStatusAccount() == 1; }
}
