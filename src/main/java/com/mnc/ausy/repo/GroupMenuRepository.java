package com.mnc.ausy.repo;

import com.mnc.ausy.domain.GroupMenu;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;

public interface GroupMenuRepository extends JpaRepository<GroupMenu, Long> {
    List<GroupMenu> findByIdGroup(Integer idGroup);
    boolean existsByIdGroupAndIdMenu(Integer idGroup, Integer idMenu);
    @Transactional void deleteByIdGroupAndIdMenu(Integer idGroup, Integer idMenu);
    @Transactional void deleteByIdMenu(Integer idMenu);
    @Transactional void deleteByIdGroup(Integer idGroup);
}
