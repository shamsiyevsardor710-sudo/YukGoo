package uz.yukgo.telegram;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import uz.yukgo.order.CargoType;
import uz.yukgo.order.OrderKind;
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
        assertEquals(BodyType.TRUCK, r.bodyType(), "tentli — yuk mashinasi");
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
        assertEquals(BodyType.TRUCK, r.bodyType(), "refrijerator — yuk mashinasi");
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
        assertEquals(BodyType.FURGON, r.bodyType(), "labo — furgon");
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

    @Test
    void passengersAndParcels() {
        var r = P.parse("Kosonsoydan Toshkentga 2 kishi ertaga ertalab", TODAY);
        assertTrue(r.isLoad());
        assertEquals(OrderKind.PASSENGER, r.kind());
        assertEquals(Integer.valueOf(2), r.seats());
        assertNull(r.weightKg());
        assertTrue(r.from().name().endsWith("Kosonsoy"), r.from().name());
        assertEquals("Toshkent shahri", region(r.to()));
        assertEquals(TODAY.plusDays(1), r.pickupDate());
        assertFalse(r.driverOffer());

        var y = P.parse("Namangan - Toshkent yo'lovchi bor, bugun", TODAY);
        assertEquals(OrderKind.PASSENGER, y.kind());
        assertEquals(Integer.valueOf(1), y.seats());

        var p = P.parse("Andijondan Toshkentga pochta, hujjatlar, bugun", TODAY);
        assertEquals(OrderKind.PARCEL, p.kind());
        assertNull(p.seats());

        var k = P.parse("Toshkent - Samarqand 20 kishilik qozon, 50 kg", TODAY);
        assertEquals(OrderKind.CARGO, k.kind(), "«20 kishilik» — yo'lovchi emas");
        assertEquals(50.0, k.weightKg());
    }

    @Test
    void driverOfferIsNotRequest() {
        var r = P.parse("Kosonsoy - Toshkent, pochta va yo'lovchi olaman, ertaga", TODAY);
        assertTrue(r.isLoad());
        assertTrue(r.driverOffer());
        assertEquals(TODAY.plusDays(1), r.pickupDate());

        var s = P.parse("Toshkentdan Andijonga 3 ta bo'sh joy bor", TODAY);
        assertTrue(s.driverOffer());
        assertEquals(Integer.valueOf(3), s.seats());

        var q = P.parse("Toshkent - Buxoro, olib ketadigan mashina kerak", TODAY);
        assertFalse(q.driverOffer());

        var ask = P.parse("Kosonsoydan Toshkentga 1 kishi, joy bormi?", TODAY);
        assertFalse(ask.driverOffer(), "«joy bormi?» — yo'lovchining savoli");
        assertEquals(OrderKind.PASSENGER, ask.kind());

        var both = P.parse("Toshkentdan Andijonga 3 ta bo'sh joy bor, yo'lovchi olaman", TODAY);
        assertTrue(both.driverOffer());
        assertEquals(Integer.valueOf(3), both.seats(), "«yo'lovchi» so'zi bo'sh o'rinlar sonini 1 ga tushirmasin");

        var want = P.parse("Kosonsoy - Toshkent, 3 ta joy bor, yo'lovchi kerak", TODAY);
        assertTrue(want.driverOffer(), "«yo'lovchi kerak» — haydovchi yo'lovchi qidiryapti");
        assertEquals(Integer.valueOf(3), want.seats());

        var car = P.parse("Kosonsoydan Toshkentga 2 kishi, joy bor mashina kerak", TODAY);
        assertFalse(car.driverOffer(), "«mashina kerak» — so'rov");
        assertEquals(OrderKind.PASSENGER, car.kind());
        assertEquals(Integer.valueOf(2), car.seats());

        var free = P.parse("Andijon - Toshkent, pochta olaman, bugun", TODAY);
        assertTrue(free.driverOffer());
        assertNull(free.seats(), "o'rin soni yozilmagan — taxmin qilinmaydi");
    }

    @Test
    void cargoWithPeopleIsStillCargo() {
        var a = P.parse("Toshkent - Samarqand, 5 tonna mebel, yuklashga 2 ta odam kerak, 3 mln", TODAY);
        assertEquals(OrderKind.CARGO, a.kind());
        assertEquals(5000.0, a.weightKg());
        assertNull(a.seats());

        var b = P.parse("Ташкент - Самарканд, груз 3 места, 200 кг", TODAY);
        assertEquals(OrderKind.CARGO, b.kind());
        assertEquals(200.0, b.weightKg());

        var c = P.parse("Ташкент - Бухара, 5 тонн, нужен 1 человек грузчик", TODAY);
        assertEquals(OrderKind.CARGO, c.kind());
        assertEquals(5000.0, c.weightKg());

        var d = P.parse("Andijon - Toshkent, 10 tonna un, hujjatlari bor", TODAY);
        assertEquals(OrderKind.CARGO, d.kind(), "og'ir yuk «hujjatlari bor» bo'lsa ham pochta emas");
        assertEquals(10000.0, d.weightKg());

        var e = P.parse("Namangan - Toshkent mebel ko'chirish, 2 ta odam yordam beradi", TODAY);
        assertEquals(OrderKind.CARGO, e.kind());

        var f = P.parse("Andijon - Toshkent pochta 2 kg, bugun", TODAY);
        assertEquals(OrderKind.PARCEL, f.kind(), "yengil jo'natma — pochta");
    }
}
