package com.mnc.ausy.repo;

import com.mnc.ausy.domain.GroupUser;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface GroupUserRepository extends JpaRepository<GroupUser, Integer> {
    List<GroupUser> findAllByOrderByNamaGroupAsc();
}
