package com.mnc.ausy.service;

import com.mnc.ausy.domain.Menu;
import com.mnc.ausy.repo.MenuRepository;
import org.springframework.stereotype.Service;

import java.util.*;

@Service
public class MenuService {

    private final MenuRepository menus;

    public MenuService(MenuRepository menus) { this.menus = menus; }

    /** Parent menus (with their accessible children) for a group's sidebar. */
    public List<Menu> sidebarFor(Integer idGroup) {
        if (idGroup == null) return List.of();
        List<Menu> children = menus.findAccessibleChildren(idGroup);

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
