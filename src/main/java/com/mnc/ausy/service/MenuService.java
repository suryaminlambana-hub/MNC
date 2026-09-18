package com.mnc.ausy.service;

import com.mnc.ausy.domain.Menu;
import com.mnc.ausy.repo.MenuRepository;
import org.springframework.stereotype.Service;

import java.util.*;

@Service
public class MenuService {

    private final MenuRepository menus;

    public MenuService(MenuRepository menus) { this.menus = menus; }

    /**
     * Sidebar milik SATU user. Hak akses melekat pada user sejak BUG_AUSY_00143, jadi dua user
     * dengan role sama bisa punya sidebar berbeda.
     */
    public List<Menu> sidebarForUser(String userId) {
        if (userId == null) return List.of();
        return group(menus.findAccessibleChildrenForUser(userId));
    }

    private List<Menu> group(List<Menu> children) {
        // group children under their parent id, preserving order
        Map<Integer, List<Menu>> byParent = new LinkedHashMap<>();
        for (Menu c : children) {
            byParent.computeIfAbsent(c.getParent(), k -> new ArrayList<>()).add(c);
        }

        List<Menu> parents = new ArrayList<>();
        for (Map.Entry<Integer, List<Menu>> e : byParent.entrySet()) {
            menus.findById(e.getKey()).ifPresent(p -> {
                p.setChildren(e.getValue());
                parents.add(p);
            });
        }
        parents.sort(Comparator.comparingInt(m -> m.getUrutan() == null ? 0 : m.getUrutan()));
        return parents;
    }
}
