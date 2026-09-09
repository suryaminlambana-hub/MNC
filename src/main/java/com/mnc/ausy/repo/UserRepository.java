package com.mnc.ausy.repo;

import com.mnc.ausy.domain.User;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;

public interface UserRepository extends JpaRepository<User, Long> {
    Optional<User> findByUserId(String userId);
    boolean existsByUserId(String userId);
    /** Berapa user yang terpasang ke sebuah group — group yang terpakai tidak boleh dihapus. */
    long countByIdGroup(Integer idGroup);
    /** User aktif dalam sebuah group — approver dianggap ada kalau angkanya > 0. */
    long countByIdGroupAndStatusAccount(Integer idGroup, Short statusAccount);
}
