package uz.yukgo.telegram;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import uz.yukgo.order.CargoType;
import uz.yukgo.user.BodyType;

import java.io.InputStream;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Botga oddiy yozilgan yuk e'lonini tushunish (viloyat va tumanlar — data/regions.json dan, ID lar tartib bo'yicha). */
class LoadTextParserTest {
    private static final LocalDate TODAY = LocalDate.of(2026, 10, 22);
    private static final LoadTextParser P = parser();

    private static LoadTextParser parser() {
        try (InputStream in = LoadTextParserTest.class.getResourceAsStream("/data/regions.json")) {
            JsonNode arr = new ObjectMapper().readTree(in);
            List<LoadTextParser.RegionInfo> list = new ArrayList<>();
            long rid = 1, did = 1;
            for (JsonNode r : arr) {
                Map<Long, String> ds = new LinkedHashMap<>();
                for (JsonNode d : r.path("districts")) ds.put(did++, d.asText());
                list.add(new LoadTextParser.RegionInfo(rid++, r.path("name").asText(), ds));
            }
            return new LoadTextParser(list);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String region(LoadTextParser.Place p) {
        return p == null ? null : p.name().split(",")[0];
    }

    @Test
    void typicalGroupPost() {
        var r = P.parse("Toshkent - Samarqand 5 tonna mebel, tent kerak, ertaga. 3 mln. Tel +998 90 123 45 67", TODAY);
        assertTrue(r.isLoad());
        assertEquals("Toshkent shahri", region(r.from()));
        assertEquals("Samarqand viloyati", region(r.to()));
        assertEquals(5000.0, r.weightKg());
        assertEquals(Long.valueOf(3_000_000L), r.price());
        assertEquals(TODAY.plusDays(1), r.pickupDate());
        assertEquals(BodyType.TENT, r.bodyType());
        assertEquals(CargoType.FURNITURE, r.cargoType());
        assertFalse(r.title().contains("123"), "telefon sarlavhaga tushmasin");
        assertFalse(r.description().contains("45 67"), "telefon tavsifga tushmasin");
    }

    @Test
    void suffixesDecideDirection() {
        var r = P.parse("Samarqandga Buxorodan 20 qop sement bor, 450 000 so'm", TODAY);
        assertEquals("Buxoro viloyati", region(r.from()));
        assertEquals("Samarqand viloyati", region(r.to()));
        assertEquals(Long.valueOf(450_000L), r.price());
        assertEquals(CargoType.BUILDING_MATERIALS, r.cargoType());
    }

    @Test
    void cyrillicAndRussian() {
        var r = P.parse("Ташкент — Фергана, 10т, реф, 25.10, 4 500 000 сум", TODAY);
        assertEquals("Toshkent shahri", region(r.from()));
        assertEquals("Farg'ona viloyati", region(r.to()));
        assertEquals(10000.0, r.weightKg());
        assertEquals(BodyType.REFRIGERATOR, r.bodyType());
        assertEquals(LocalDate.of(2026, 10, 25), r.pickupDate());
        assertEquals(Long.valueOf(4_500_000L), r.price());

        var u = P.parse("Тошкентдан Термизга юк бор, 2 тонна, нарх келишилади", TODAY);
        assertEquals("Toshkent shahri", region(u.from()));
        assertEquals("Surxondaryo viloyati", region(u.to()));
        assertEquals(2000.0, u.weightKg());
        assertNull(u.price());
        assertTrue(u.negotiable());
    }

    @Test
    void cityDistrictsAndMonthNames() {
        var r = P.parse("Toshkent Chilonzor - Yunusobod uy ko'chirish, labo kerak, 28-oktabr, 300 ming", TODAY);
        assertTrue(r.isLoad());
        assertNotNull(r.from().districtId());
        assertTrue(r.from().name().contains("Chilonzor"));
        assertTrue(r.to().name().contains("Yunusobod"));
        assertEquals(BodyType.MINIVAN, r.bodyType());
        assertEquals(CargoType.HOUSEHOLD, r.cargoType());
        assertEquals(LocalDate.of(2026, 10, 28), r.pickupDate());
        assertEquals(Long.valueOf(300_000L), r.price());
    }

    @Test
    void citiesInsideOneRegion() {
        var r = P.parse("Termiz Denov 5 tonna qum samosval 1,2 mln", TODAY);
        assertTrue(r.isLoad(), "bir viloyat ichidagi ikki shahar ham yo'nalish");
        assertTrue(r.from().name().endsWith("Termiz shahri"));
        assertTrue(r.to().name().endsWith("Denov"));
        assertEquals(Long.valueOf(1_200_000L), r.price());

        var u = P.parse("Urganchdan Xivaga kartoshka 2 t ertaga", TODAY);
        assertTrue(u.from().name().endsWith("Urganch shahri"));
        assertTrue(u.to().name().endsWith("Xiva shahri"));
        assertEquals(CargoType.PRODUCE, u.cargoType());
    }

    @Test
    void cityBeforeDistrictMeansRegion() {
        var r = P.parse("Andijon Asaka \u2192 Toshkent Sergeli, 800 kg, Damas, bugun, 250 000 so'm", TODAY);
        assertTrue(r.isLoad());
        assertTrue(r.from().name().endsWith("Asaka"), r.from().name());
        assertTrue(r.to().name().endsWith("Sergeli"), r.to().name());
        assertEquals(800.0, r.weightKg());
        assertEquals(TODAY, r.pickupDate());

        var u = P.parse("Buxoro Kogon -> Samarqand\n3t mebel", TODAY);
        assertTrue(u.from().name().endsWith("Kogon shahri"), u.from().name());
        assertEquals("Samarqand viloyati", region(u.to()));

        var w = P.parse("Samarqanddan Urgutga 2 tonna", TODAY);
        assertTrue(w.from().name().endsWith("Samarqand shahri"));
        assertTrue(w.to().name().endsWith("Urgut"));
    }

    @Test
    void notALoad() {
        assertFalse(P.parse("Salom, qanday ishlaydi?", TODAY).isLoad());
        assertFalse(P.parse("Toshkentda yuk bormi", TODAY).isLoad(), "bitta joy — yo'nalish emas");
        var r = P.parse("Olmaliq - Angren 5 tonna tosh", TODAY);
        assertNotEquals(CargoType.PRODUCE, r.cargoType(), "Olmaliq shahri 'olma' deb o'qilmasin");
    }

    @Test
    void phoneIsNotPrice() {
        var r = P.parse("Andijon - Toshkent 901234567 isuzu", TODAY);
        assertTrue(r.isLoad());
        assertNull(r.price());
    }
}
