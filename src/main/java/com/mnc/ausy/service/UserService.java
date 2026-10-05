package com.mnc.ausy.service;

import com.mnc.ausy.domain.User;
import com.mnc.ausy.repo.UserRepository;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

@Service
public class UserService {
    private final UserRepository users;
    private final PasswordEncoder encoder;

    public UserService(UserRepository users, PasswordEncoder encoder) {
        this.users = users; this.encoder = encoder;
    }

    private String levelOf(Integer idGroup) {
        if (idGroup == null) return "USER";
        return switch (idGroup) { case 1 -> "ADMIN"; case 2 -> "SUPERADMIN"; case 3 -> "SPV"; default -> "USER"; };
    }

    public boolean add(String userId, String rawPass, String nama, String email, Integer idGroup, String addby) {
        userId = userId.toLowerCase().trim();
        if (userId.isEmpty() || users.existsByUserId(userId)) return false;
        User u = new User();
        u.setUserId(userId);
        u.setPass(encoder.encode(rawPass));
        u.setNama(nama);
        u.setEmail(email);
        u.setIdGroup(idGroup);
        u.setLevel(levelOf(idGroup));
        u.setStatusAccount((short) 1);          // new users active
        u.setCreDate(LocalDateTime.now());
        u.setAddby(addby);
        users.save(u);
        return true;
    }

    public void edit(String userId, String nama, String email, Integer idGroup, Short status, String rawPass) {
        users.findByUserId(userId).ifPresent(u -> {
            u.setNama(nama);
            u.setEmail(email);
            u.setIdGroup(idGroup);
            u.setLevel(levelOf(idGroup));
            u.setStatusAccount(status);
            if (rawPass != null && !rawPass.isBlank()) u.setPass(encoder.encode(rawPass));
            users.save(u);
        });
    }

    public void delete(String userId) {
        users.findByUserId(userId).ifPresent(users::delete);
    }
}
