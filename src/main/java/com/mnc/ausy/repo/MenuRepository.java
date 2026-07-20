package com.mnc.ausy.repo;

import com.mnc.ausy.domain.Menu;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.List;

public interface MenuRepository extends JpaRepository<Menu, Integer> {

    @Query("""
           select m from Menu m
           where m.active = 1 and m.parent > 0
             and m.idMenu in (select gm.idMenu from GroupMenu gm where gm.idGroup = :g)
           order by m.urutan asc, m.namaMenu asc
           """)
    List<Menu> findAccessibleChildren(@Param("g") Integer idGroup);

    List<Menu> findByParentOrderByUrutanAsc(Integer parent);
    List<Menu> findAllByOrderByParentAscUrutanAsc();
}
