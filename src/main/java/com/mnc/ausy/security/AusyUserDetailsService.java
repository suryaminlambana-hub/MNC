package com.mnc.ausy.security;

import com.mnc.ausy.repo.UserRepository;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

@Service
public class AusyUserDetailsService implements UserDetailsService {

    private final UserRepository users;

    public AusyUserDetailsService(UserRepository users) { this.users = users; }

    @Override
    public UserDetails loadUserByUsername(String username) {
        return users.findByUserId(username.toLowerCase().trim())
                .map(AusyUserDetails::new)
                .orElseThrow(() -> new UsernameNotFoundException("User tidak ditemukan: " + username));
    }
}
