package com.mnc.ausy.repo;

import com.mnc.ausy.domain.Menu;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.List;

public interface MenuRepository extends JpaRepository<Menu, Integer> {

    /** Semua item akses (menu/aksi/card) yang dipegang sebuah group — cetakan role. */
    @Query("""
           select m from Menu m
           where m.active = 1
             and m.idMenu in (select gm.idMenu from GroupMenu gm where gm.idGroup = :g)
           """)
    List<Menu> findGrantedItems(@Param("g") Integer idGroup);

    /** Menu sidebar milik SATU user — hak yang benar-benar berlaku (BUG_AUSY_00143). */
    @Query("""
           select m from Menu m
           where m.active = 1 and m.parent > 0 and m.kind = 'MENU'
             and m.idMenu in (select um.idMenu from UserMenu um where um.userId = :u)
           order by m.urutan asc, m.namaMenu asc
           """)
    List<Menu> findAccessibleChildrenForUser(@Param("u") String userId);

    /** Semua item akses (menu/aksi/card) yang dipegang SATU user. */
    @Query("""
           select m from Menu m
           where m.active = 1
             and m.idMenu in (select um.idMenu from UserMenu um where um.userId = :u)
           """)
    List<Menu> findGrantedItemsForUser(@Param("u") String userId);

    /** Semua URL yang "dimiliki" sebuah item akses — dipakai untuk mencari pemilik terpanjang. */
    @Query("select m from Menu m where m.active = 1 and m.url is not null and m.kind <> 'CARD'")
    List<Menu> findUrlOwners();

    List<Menu> findByKindOrderByParentAscUrutanAsc(String kind);

    /** Aksi yang menempel pada sebuah menu (ikut otomatis saat menunya diberikan). */
    List<Menu> findByBundledWithIsNotNull();

    List<Menu> findByParentOrderByUrutanAsc(Integer parent);
    List<Menu> findAllByOrderByParentAscUrutanAsc();
}
