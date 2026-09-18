package com.mnc.ausy.repo;

import com.mnc.ausy.domain.UserMenu;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

public interface UserMenuRepository extends JpaRepository<UserMenu, Long> {
    List<UserMenu> findByUserId(String userId);
    boolean existsByUserIdAndIdMenu(String userId, Integer idMenu);
    long countByIdMenu(Integer idMenu);
    @Transactional void deleteByUserIdAndIdMenu(String userId, Integer idMenu);
    @Transactional void deleteByUserId(String userId);
    @Transactional void deleteByIdMenu(Integer idMenu);
}
