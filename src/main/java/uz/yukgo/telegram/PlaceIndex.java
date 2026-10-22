package uz.yukgo.telegram;

import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import uz.yukgo.location.District;
import uz.yukgo.location.DistrictRepository;
import uz.yukgo.location.Region;
import uz.yukgo.location.RegionRepository;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Botga yozilgan e'lonni tushunish uchun viloyat va tumanlar ro'yxati (bazadan bir marta o'qiladi).
 * Viloyat/tumanlar ilova ishlayotganda o'zgarmaydi — keyingi ishga tushishda yangidan yig'iladi.
 */
@Component
@RequiredArgsConstructor
public class PlaceIndex {
    private final RegionRepository regions;
    private final DistrictRepository districts;
    private volatile LoadTextParser parser;

    @Transactional(readOnly = true)
    public LoadTextParser parser() {
        LoadTextParser p = parser;
        if (p != null) return p;
        synchronized (this) {
            if (parser == null) parser = build();
            return parser;
        }
    }

    private LoadTextParser build() {
        Map<Long, Map<Long, String>> byRegion = new LinkedHashMap<>();
        List<Region> rs = regions.findAllByOrderBySortOrderAsc();
        for (Region r : rs) byRegion.put(r.getId(), new LinkedHashMap<>());
        // ID tartibida (seed fayldagi tartib): bir xil nomli tumanlardan birinchisi ustun bo'ladi
        for (District d : districts.findAll(Sort.by("id"))) {
            Map<Long, String> m = byRegion.get(d.getRegion().getId());
            if (m != null) m.put(d.getId(), d.getName());
        }
        List<LoadTextParser.RegionInfo> list = new ArrayList<>();
        for (Region r : rs) list.add(new LoadTextParser.RegionInfo(r.getId(), r.getName(), byRegion.get(r.getId())));
        return new LoadTextParser(list);
    }
}
