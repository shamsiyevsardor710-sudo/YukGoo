package uz.yukgo;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * To'liq oqim: ro'yxatdan o'tish (dev-kod bilan) → buyurtma → taklif → qabul → yo'lda → topshirish → baho,
 * admin/ega huquqlari, foydalanish shartlari va bloklash.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class FullFlowTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper om;
    @Autowired uz.yukgo.order.OrderReminders reminders;

    private JsonNode call(String method, String url, Object body, String token, int expected) throws Exception {
        var req = switch (method) {
            case "GET" -> get(url);
            case "PUT" -> put(url);
            case "DELETE" -> delete(url);
            default -> post(url);
        };
        req.contentType(MediaType.APPLICATION_JSON);
        if (body != null) req.content(om.writeValueAsString(body));
        if (token != null) req.header("Authorization", "Bearer " + token);
        ResultActions r = mvc.perform(req).andExpect(status().is(expected));
        String s = r.andReturn().getResponse().getContentAsString();
        return s.isBlank() ? om.createObjectNode() : om.readTree(s);
    }

    private long firstRegion() throws Exception {
        return call("GET", "/api/locations/regions", null, null, 200).get(0).get("id").asLong();
    }

    private long firstDistrict(long regionId) throws Exception {
        return call("GET", "/api/locations/regions/" + regionId + "/districts", null, null, 200).get(0).get("id").asLong();
    }

    private String register(String phone, String first, String last) throws Exception {
        return register(phone, first, last, true);
    }

    /** @param acceptTerms false — shartlar paydo bo'lishidan oldingi (eski) hisob kabi */
    private String register(String phone, String first, String last, boolean acceptTerms) throws Exception {
        JsonNode start = call("POST", "/api/auth/register/start", Map.of("phone", phone), null, 200);
        String requestId = start.get("requestId").asText();
        String code = start.get("devCode").asText();
        call("POST", "/api/auth/verify", Map.of("requestId", requestId, "code", code), null, 200);
        long r = firstRegion();
        // Faqat viloyat va tuman (qishloq va uy manzili endi so'ralmaydi)
        JsonNode res = call("POST", "/api/auth/register/complete", Map.of(
                "requestId", requestId, "firstName", first, "lastName", last, "password", "secret123",
                "regionId", r, "districtId", firstDistrict(r), "acceptTerms", acceptTerms), null, 200);
        return res.get("token").asText();
    }

    @Test
    @Order(1)
    void fullDeliveryFlow() throws Exception {
        String sender = register("+998901111111", "Ali", "Valiyev");
        String driver = register("+998902222222", "Bekzod", "Toshmatov");

        // Ism-familiya + parol bilan kirish
        JsonNode login = call("POST", "/api/auth/login",
                Map.of("firstName", "ali", "lastName", "VALIYEV", "password", "secret123"), null, 200);
        assertThat(login.get("user").get("phone").asText()).isEqualTo("+998901111111");
        call("POST", "/api/auth/login", Map.of("firstName", "Ali", "lastName", "Valiyev", "password", "xato"), null, 401);

        // Haydovchi mashina qo'shadi (eski ilova modeli, tentli turi va yuk ko'tarishni yuborsa ham qabul qilinadi)
        JsonNode veh = call("PUT", "/api/users/me/vehicle", Map.of("brand", "Isuzu", "model", "NPR", "plateNumber", "01 a 123 bc",
                "bodyType", "TENT", "capacityKg", 5000), driver, 200).get("vehicle");
        assertThat(veh.get("brand").asText()).isEqualTo("Isuzu NPR");
        assertThat(veh.get("bodyType").asText()).isEqualTo("TRUCK");
        assertThat(veh.get("capacityKg").isNull()).isTrue();

        long r = firstRegion();
        JsonNode order = call("POST", "/api/orders", Map.ofEntries(
                Map.entry("title", "20 qop sement"), Map.entry("cargoType", "BUILDING_MATERIALS"),
                Map.entry("weightKg", 1000), Map.entry("from", Map.of("regionId", r, "lat", 41.3, "lng", 69.2)),
                Map.entry("to", Map.of("regionId", r + 1, "lat", 39.6, "lng", 66.9)),
                Map.entry("distanceKm", 300), Map.entry("price", 900000),
                Map.entry("receiverName", "Aziz"), Map.entry("receiverPhone", "+998933333333")), sender, 200);
        long orderId = order.get("id").asLong();
        String code = order.get("deliveryCode").asText();
        assertThat(order.get("scope").asText()).isEqualTo("INTERREGION");

        // Bozorda ko'rinadi — faqat kirganlarga; mehmonga e'lonlar, reyslar va haydovchilar yopiq, faqat umumiy sonlar
        JsonNode market = call("GET", "/api/orders", null, driver, 200);
        assertThat(market.get("total").asLong()).isGreaterThanOrEqualTo(1);
        call("GET", "/api/orders", null, null, 401);
        call("GET", "/api/trips", null, null, 401);
        call("GET", "/api/drivers", null, null, 401);
        call("GET", "/api/users/" + call("GET", "/api/users/me", null, driver, 200).get("id").asLong(), null, null, 401);
        assertThat(call("GET", "/api/stats/public", null, null, 200).get("openOrders").asLong()).isGreaterThanOrEqualTo(1);
        // Begona odam topshirish kodini ko'rmaydi
        assertThat(call("GET", "/api/orders/" + orderId, null, driver, 200).has("deliveryCode")).isFalse();
        // Ulashilgan havola ham — avval kirish yoki ro'yxatdan o'tish (ilova kirgach o'sha e'lonni ochadi)
        call("GET", "/api/orders/" + orderId, null, null, 401);
        call("GET", "/api/orders/" + orderId + "/messages", null, null, 401);
        call("GET", "/api/orders/my", null, null, 401);

        // «Olaman» dan oldin yozishma: haydovchi so'raydi — raqamlar yopiq; e'lon egasida yozganlar ro'yxati
        JsonNode q1 = call("POST", "/api/orders/" + orderId + "/messages", Map.of("text", "Qayerdan olaman? Tel 93 555 66 77"), driver, 200);
        assertThat(q1.get("text").asText()).doesNotContain("555");
        JsonNode threads = call("GET", "/api/orders/" + orderId + "/threads", null, sender, 200);
        assertThat(threads.size()).isEqualTo(1);
        long driverId = threads.get(0).get("driverId").asLong();
        assertThat(threads.get(0).get("driver").has("phone")).isFalse();
        assertThat(call("GET", "/api/orders/" + orderId + "/messages?driverId=" + driverId, null, sender, 200).get(0).get("text").asText())
                .doesNotContain("555");
        call("POST", "/api/orders/" + orderId + "/messages", Map.of("text", "Bozor oldidan", "driverId", driverId), sender, 200);
        call("GET", "/api/orders/" + orderId + "/threads", null, driver, 403);

        // «Xabarlar»: haydovchida egasining javobi o'qilmagan; yozishmani ochgach — 0. Egasi haydovchi xabarini o'qigan
        assertThat(call("GET", "/api/messages/unread-count", null, driver, 200).get("count").asLong()).isEqualTo(1);
        JsonNode inbox = call("GET", "/api/messages", null, driver, 200);
        assertThat(inbox.size()).isEqualTo(1);
        assertThat(inbox.get(0).get("unread").asInt()).isEqualTo(1);
        assertThat(inbox.get(0).get("orderId").asLong()).isEqualTo(orderId);
        assertThat(inbox.get(0).get("counterpart").has("phone")).isFalse();
        call("GET", "/api/orders/" + orderId + "/messages", null, driver, 200);
        assertThat(call("GET", "/api/messages/unread-count", null, driver, 200).get("count").asLong()).isZero();
        assertThat(call("GET", "/api/messages/unread-count", null, sender, 200).get("count").asLong()).isZero();
        JsonNode senderInbox = call("GET", "/api/messages", null, sender, 200);
        assertThat(senderInbox.get(0).get("lastText").asText()).isEqualTo("Bozor oldidan");
        assertThat(senderInbox.get(0).get("iAmSender").asBoolean()).isTrue();
        call("GET", "/api/messages", null, null, 401);

        // Push: qurilma ro'yxati (Firebase sinovda sozlanmagan — faqat saqlanadi)
        call("POST", "/api/push/register", Map.of("token", "test-device-token-1", "platform", "android"), driver, 200);
        call("POST", "/api/push/register", Map.of("token", "test-device-token-1"), driver, 200);
        call("POST", "/api/push/register", Map.of("token", ""), driver, 400);
        call("POST", "/api/push/register", Map.of("token", "x"), null, 401);
        call("POST", "/api/push/unregister", Map.of("token", "test-device-token-1"), sender, 200);   // begona — o'chmaydi

        // Taklif → qabul
        JsonNode offer = call("POST", "/api/orders/" + orderId + "/offers", Map.of("price", 850000, "comment", "Ertaga olaman"), driver, 200);
        call("POST", "/api/orders/" + orderId + "/offers", Map.of("price", 1000), sender, 400); // yuboruvchida mashina yo'q
        JsonNode accepted = call("POST", "/api/orders/" + orderId + "/offers/" + offer.get("id").asLong() + "/accept", null, sender, 200);
        assertThat(accepted.get("status").asText()).isEqualTo("ACCEPTED");
        assertThat(accepted.get("finalPrice").asLong()).isEqualTo(850000);

        // Yo'lda, GPS, yozishma
        call("POST", "/api/orders/" + orderId + "/pickup", null, driver, 200);
        call("POST", "/api/orders/" + orderId + "/track", Map.of("lat", 40.5, "lng", 68.0, "speedKmh", 80), driver, 200);
        assertThat(call("GET", "/api/orders/" + orderId + "/track", null, sender, 200).get("last").get("lat").asDouble()).isEqualTo(40.5);
        call("POST", "/api/orders/" + orderId + "/messages", Map.of("text", "Yo'ldaman"), driver, 200);
        // Haydovchi tanlangach — oldingi yozishma shu yerda va raqamlar ochiq
        JsonNode chat = call("GET", "/api/orders/" + orderId + "/messages", null, sender, 200);
        assertThat(chat.size()).isEqualTo(3);
        assertThat(chat.get(0).get("text").asText()).contains("93 555 66 77");

        // Noto'g'ri kod bilan topshirib bo'lmaydi, to'g'risi bilan bo'ladi
        call("POST", "/api/orders/" + orderId + "/deliver", Map.of("code", "0000".equals(code) ? "1111" : "0000"), driver, 400);
        call("POST", "/api/orders/" + orderId + "/deliver", Map.of("code", code), driver, 200);

        // Baholash
        call("POST", "/api/orders/" + orderId + "/review", Map.of("stars", 5, "comment", "Zo'r"), sender, 200);
        call("POST", "/api/orders/" + orderId + "/review", Map.of("stars", 5), sender, 409);
        JsonNode driverProfile = call("GET", "/api/users/me", null, driver, 200);
        assertThat(driverProfile.get("rating").asDouble()).isEqualTo(5.0);
        assertThat(driverProfile.get("deliveriesCompleted").asInt()).isEqualTo(1);
    }

    @Test
    @Order(2)
    void adminAndOwnerPermissions() throws Exception {
        String owner = call("POST", "/api/auth/login", Map.of("phone", "+998900000000", "password", "owner12345"), null, 200)
                .get("token").asText();
        String userA = register("+998904444444", "Admin", "Bo'ladigan");
        String userB = register("+998905555555", "Oddiy", "Foydalanuvchi");
        long idA = call("GET", "/api/users/me", null, userA, 200).get("id").asLong();
        long idB = call("GET", "/api/users/me", null, userB, 200).get("id").asLong();
        long ownerId = call("GET", "/api/users/me", null, owner, 200).get("id").asLong();

        // Oddiy foydalanuvchi admin paneliga kira olmaydi
        call("GET", "/api/admin/stats", null, userB, 403);

        // Ega A ni admin qiladi
        call("POST", "/api/admin/users/" + idA + "/role", Map.of("role", "ADMIN"), owner, 200);
        String adminA = call("POST", "/api/auth/login", Map.of("phone", "+998904444444", "password", "secret123"), null, 200)
                .get("token").asText();

        // Admin oddiy foydalanuvchini bloklaydi — u kira olmaydi
        call("POST", "/api/admin/users/" + idB + "/block", Map.of("reason", "Spam"), adminA, 200);
        call("GET", "/api/users/me", null, userB, 403);
        call("POST", "/api/auth/login", Map.of("phone", "+998905555555", "password", "secret123"), null, 403);
        call("POST", "/api/admin/users/" + idB + "/unblock", null, adminA, 200);

        // Admin egani ham, rol berishni ham qila olmaydi
        call("POST", "/api/admin/users/" + ownerId + "/block", null, adminA, 403);
        call("POST", "/api/admin/users/" + idB + "/role", Map.of("role", "ADMIN"), adminA, 403);

        // Faqat ega adminni chiqarib yuboradi
        call("POST", "/api/admin/users/" + idA + "/remove", Map.of("reason", "test"), owner, 200);
        call("GET", "/api/users/me", null, adminA, 403);
    }

    @Test
    @Order(3)
    void termsAndBlocking() throws Exception {
        // Shartlarni qabul qilmagan (eski) hisob kontent yarata olmaydi — 428; qabul qilgach oladi
        String sender = register("+998906666666", "Eski", "Hisob", false);
        assertThat(call("GET", "/api/users/me", null, sender, 200).get("termsAccepted").asBoolean()).isFalse();
        long r = firstRegion();
        var newOrder = Map.ofEntries(
                Map.entry("title", "10 qop un"), Map.entry("cargoType", "BUILDING_MATERIALS"),
                Map.entry("weightKg", 500), Map.entry("from", Map.of("regionId", r, "lat", 41.3, "lng", 69.2)),
                Map.entry("to", Map.of("regionId", r + 1, "lat", 39.6, "lng", 66.9)),
                Map.entry("distanceKm", 300), Map.entry("price", 700000),
                Map.entry("receiverName", "Aziz"), Map.entry("receiverPhone", "+998933333333"));
        call("POST", "/api/orders", newOrder, sender, 428);
        assertThat(call("POST", "/api/users/me/terms", null, sender, 200).get("termsAccepted").asBoolean()).isTrue();
        long orderId = call("POST", "/api/orders", newOrder, sender, 200).get("id").asLong();

        // Yuboruvchi haydovchini bloklaydi: taklif ham, olish ham yopiq, bozorda e'lon ko'rinmaydi
        String driver = register("+998907777777", "Blok", "Haydovchi");
        call("PUT", "/api/users/me/vehicle", Map.of("brand", "Isuzu", "plateNumber", "01 b 777 bc",
                "bodyType", "TRUCK"), driver, 200);
        long driverId = call("GET", "/api/users/me", null, driver, 200).get("id").asLong();
        call("POST", "/api/users/" + driverId + "/block", null, sender, 200);
        assertThat(call("GET", "/api/users/me/blocks", null, sender, 200).size()).isEqualTo(1);
        assertThat(call("GET", "/api/users/" + driverId, null, sender, 200).get("blockedByMe").asBoolean()).isTrue();
        call("POST", "/api/orders/" + orderId + "/offers", Map.of("price", 650000), driver, 403);
        call("POST", "/api/orders/" + orderId + "/take", null, driver, 403);
        boolean visible = false;
        for (JsonNode o : call("GET", "/api/orders", null, driver, 200).get("items")) visible |= o.get("id").asLong() == orderId;
        assertThat(visible).isFalse();

        // Blokdan chiqarilgach hammasi odatdagidek
        call("DELETE", "/api/users/" + driverId + "/block", null, sender, 200);
        assertThat(call("GET", "/api/users/me/blocks", null, sender, 200).size()).isEqualTo(0);
        call("POST", "/api/orders/" + orderId + "/offers", Map.of("price", 650000), driver, 200);
    }

    @Test
    @Order(4)
    void passengerTripMatching() throws Exception {
        String driver = register("+998908888881", "Taksi", "Haydovchi");
        call("PUT", "/api/users/me/vehicle", Map.of("brand", "Chevrolet Cobalt", "plateNumber", "50 a 123 bc", "bodyType", "SEDAN"), driver, 200);
        long r = firstRegion();
        long d = firstDistrict(r);
        // Har kuni qatnaydigan reys: hozircha mos e'lon yo'q
        JsonNode trip = call("POST", "/api/trips", Map.of("fromRegionId", r, "fromDistrictId", d, "toRegionId", r + 1,
                "departAt", LocalDate.now(ZoneId.of("Asia/Tashkent")) + "T07:00:00", "daily", true, "seats", 3), driver, 200);
        assertThat(trip.get("daily").asBoolean()).isTrue();
        assertThat(trip.get("matches").asInt()).isZero();

        // Yo'lovchi faqat yo'nalish va necha kishi ekanini yozadi
        String passenger = register("+998908888882", "Yolovchi", "Odam");
        JsonNode p = call("POST", "/api/orders", Map.of("kind", "PASSENGER", "seats", 2,
                "from", Map.of("regionId", r, "districtId", d), "to", Map.of("regionId", r + 1)), passenger, 200);
        long id = p.get("id").asLong();
        assertThat(p.get("kind").asText()).isEqualTo("PASSENGER");
        assertThat(p.get("title").asText()).isEqualTo("2 yo'lovchi");
        assertThat(p.get("receiverPhone").isNull()).isTrue();
        assertThat(p.get("pickupDate").asText()).isEqualTo(LocalDate.now(ZoneId.of("Asia/Tashkent")).toString());

        // Reysi mos haydovchiga ilova ichida ham xabar
        boolean notified = false;
        for (JsonNode n : call("GET", "/api/notifications", null, driver, 200).get("items")) {
            notified |= n.get("title").asText().contains("Siz uchun yangi yo'lovchi") && n.get("link").asText().equals("#/orders/" + id);
        }
        assertThat(notified).isTrue();

        // Bozorda tur va tuman bo'yicha
        JsonNode market = call("GET", "/api/orders?kind=PASSENGER&fromRegion=" + r + "&fromDistrict=" + d, null, driver, 200);
        assertThat(market.get("total").asLong()).isEqualTo(1);
        assertThat(market.get("items").get(0).get("id").asLong()).isEqualTo(id);
        assertThat(call("GET", "/api/orders?kind=PARCEL&fromRegion=" + r, null, driver, 200).get("total").asLong()).isZero();
        // Reyslar: avval o'z tumanidan
        assertThat(call("GET", "/api/trips?fromRegion=" + r + "&fromDistrict=" + d, null, passenger, 200).get("total").asLong()).isEqualTo(1);

        // Narxsiz «Olaman» → ikki tomonga telefon ochiladi → kodsiz «Yetib keldik»
        JsonNode taken = call("POST", "/api/orders/" + id + "/take", null, driver, 200);
        assertThat(taken.get("status").asText()).isEqualTo("ACCEPTED");
        assertThat(taken.get("finalPrice").isNull()).isTrue();
        assertThat(taken.get("sender").get("phone").asText()).isEqualTo("+998908888882");
        call("POST", "/api/orders/" + id + "/pickup", null, driver, 200);
        assertThat(call("POST", "/api/orders/" + id + "/deliver", Map.of(), driver, 200).get("status").asText()).isEqualTo("DELIVERED");

        // Yangi reys — mos e'lon egasiga xabar (keyingi e'lon uchun)
        long p2 = call("POST", "/api/orders", Map.of("kind", "PARCEL", "from", Map.of("regionId", r), "to", Map.of("regionId", r + 1)),
                passenger, 200).get("id").asLong();
        // Sanani ko'chirish: faqat egasi, o'tgan sanaga emas
        String tomorrow = LocalDate.now(ZoneId.of("Asia/Tashkent")).plusDays(1).toString();
        assertThat(call("POST", "/api/orders/" + p2 + "/reschedule", Map.of("date", tomorrow), passenger, 200).get("pickupDate").asText())
                .isEqualTo(tomorrow);
        call("POST", "/api/orders/" + p2 + "/reschedule", Map.of("date", tomorrow), driver, 403);
        call("POST", "/api/orders/" + p2 + "/reschedule", Map.of("date", LocalDate.now(ZoneId.of("Asia/Tashkent")).minusDays(1).toString()), passenger, 400);
        // Narx maslahati: ma'lumot kam — bo'sh javob; yuk — so'ralmaydi
        call("GET", "/api/orders/price-hint?kind=PASSENGER&fromRegion=" + r + "&toRegion=" + (r + 1), null, passenger, 200);
        call("GET", "/api/orders/price-hint?kind=CARGO&fromRegion=" + r + "&toRegion=" + (r + 1), null, passenger, 200);
        // Ertalabki xulosa va sanasi o'tgan e'lon so'rovi haqiqiy bazada ishlaydi (Telegram sinovda o'chiq)
        reminders.morningDigest();
        reminders.askAboutLateOrders();
        JsonNode trip2 = call("POST", "/api/trips", Map.of("fromRegionId", r, "toRegionId", r + 1,
                "departAt", LocalDate.now(ZoneId.of("Asia/Tashkent")).plusDays(1) + "T09:00:00", "seats", 2), driver, 200);
        assertThat(trip2.get("matches").asInt()).isEqualTo(1);
        boolean told = false;
        for (JsonNode n : call("GET", "/api/notifications", null, passenger, 200).get("items")) {
            told |= n.get("title").asText().contains("Yo'nalishingizda haydovchi bor") && n.get("link").asText().equals("#/orders/" + p2);
        }
        assertThat(told).isTrue();
    }

    @Test
    @Order(5)
    void routePagesAndSitemap() throws Exception {
        String sitemap = mvc.perform(get("/sitemap.xml")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(sitemap).contains("<urlset").contains("/yo/toshkent-shahri/samarqand");
        String page = mvc.perform(get("/yo/toshkent-shahri/samarqand")).andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        assertThat(page).contains("Toshkent shahri → Samarqand").doesNotContain("+998");
        mvc.perform(get("/yo/toshkent-shahri/moskva")).andExpect(status().isNotFound());
        assertThat(mvc.perform(get("/robots.txt")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString())
                .contains("Sitemap:");
    }

    @Test
    @Order(6)
    void supportContactAndNotificationCleanup() throws Exception {
        String owner = call("POST", "/api/auth/login", Map.of("phone", "+998900000000", "password", "owner12345"), null, 200)
                .get("token").asText();
        String user = register("+998901010101", "Murojaat", "Qiluvchi");
        String driver = register("+998901010102", "Murojaat", "Haydovchi");
        call("PUT", "/api/users/me/vehicle", Map.of("brand", "Cobalt", "plateNumber", "01 a 101 ab", "bodyType", "SEDAN"), driver, 200);

        // «Biz bilan bog'lanish»: foydalanuvchidan va haydovchidan — ega «Foydalanuvchilardan» / «Haydovchilardan» bo'limida
        call("POST", "/api/support", Map.of("topic", "PROBLEM", "text", "Taklif yuborganda xato chiqdi"), user, 200);
        call("POST", "/api/support", Map.of("topic", "IDEA", "text", "Qaytish reysini ham qo'shing"), driver, 200);
        call("POST", "/api/support", Map.of("topic", "IDEA", "text", "   "), user, 400);
        call("POST", "/api/support", Map.of("text", "Turi ko'rsatilmagan"), user, 400);
        call("POST", "/api/support", Map.of("topic", "IDEA", "text", "Mehmondan"), null, 401);
        call("GET", "/api/admin/support", null, user, 403);
        JsonNode counts = call("GET", "/api/admin/support/counts", null, owner, 200);
        assertThat(counts.get("users").asLong()).isGreaterThanOrEqualTo(1);
        assertThat(counts.get("drivers").asLong()).isGreaterThanOrEqualTo(1);
        JsonNode first = call("GET", "/api/admin/support?from=users&status=NEW", null, owner, 200).get("items").get(0);
        assertThat(first.get("text").asText()).isEqualTo("Taklif yuborganda xato chiqdi");
        assertThat(first.get("fromDriver").asBoolean()).isFalse();
        assertThat(first.get("author").get("phone").asText()).isEqualTo("+998901010101");
        assertThat(call("GET", "/api/admin/support?from=drivers", null, owner, 200).get("items").get(0).get("author")
                .get("vehicle").asText()).contains("Cobalt");
        call("GET", "/api/admin/support?from=hammasi", null, owner, 400);
        boolean told = false;
        for (JsonNode n : call("GET", "/api/notifications", null, owner, 200).get("items")) told |= n.get("title").asText().startsWith("📩 Muammo");
        assertThat(told).isTrue();

        // Javob: muallifga bildirishnoma va «Mening murojaatlarim»da (kim javob bergani ko'rsatilmaydi)
        long id = first.get("id").asLong();
        JsonNode replied = call("POST", "/api/admin/support/" + id + "/reply", Map.of("text", "Tuzatdik, qayta urinib ko'ring"), owner, 200);
        assertThat(replied.get("status").asText()).isEqualTo("ANSWERED");
        JsonNode mine = call("GET", "/api/support", null, user, 200);
        assertThat(mine.get(0).get("reply").asText()).isEqualTo("Tuzatdik, qayta urinib ko'ring");
        assertThat(mine.get(0).has("repliedByName")).isFalse();
        call("POST", "/api/admin/support/" + id + "/close", null, owner, 200);
        assertThat(call("GET", "/api/support", null, user, 200).get(0).get("status").asText()).isEqualTo("CLOSED");
        // Bir kunda ko'pi bilan 5 ta
        for (int i = 1; i <= 4; i++) call("POST", "/api/support", Map.of("topic", "QUESTION", "text", "Savol raqam " + i), user, 200);
        call("POST", "/api/support", Map.of("topic", "QUESTION", "text", "Oltinchi savol"), user, 400);

        // 🔔 «Hammasini o'chirish» — faqat o'zinikilar
        assertThat(call("GET", "/api/notifications", null, user, 200).get("total").asLong()).isGreaterThanOrEqualTo(1);
        long ownerNotes = call("GET", "/api/notifications", null, owner, 200).get("total").asLong();
        call("DELETE", "/api/notifications", null, user, 200);
        assertThat(call("GET", "/api/notifications", null, user, 200).get("total").asLong()).isZero();
        assertThat(call("GET", "/api/notifications/unread-count", null, user, 200).get("count").asLong()).isZero();
        assertThat(call("GET", "/api/notifications", null, owner, 200).get("total").asLong()).isEqualTo(ownerNotes);
        call("DELETE", "/api/notifications", null, null, 401);

        // «Faqat reyslarimga mos e'lonlar»: reysiga mos e'lon haqida xabar keladi; ertalabki xulosa uning reyslari bilan
        // haqiqiy bazada ishlaydi (push qurilmasi bor — xulosa oladiganlar ro'yxatiga tushadi)
        assertThat(call("GET", "/api/users/me", null, driver, 200).get("routeOnly").asBoolean()).isFalse();
        assertThat(call("PUT", "/api/users/me/settings", Map.of("routeOnly", true), driver, 200).get("routeOnly").asBoolean()).isTrue();
        call("POST", "/api/push/register", Map.of("token", "route-only-device-1", "platform", "android"), driver, 200);
        long r = firstRegion();
        call("POST", "/api/trips", Map.of("fromRegionId", r, "toRegionId", r + 1,
                "departAt", LocalDate.now(ZoneId.of("Asia/Tashkent")) + "T07:00:00", "daily", true, "seats", 3), driver, 200);
        long onRoute = call("POST", "/api/orders", Map.of("kind", "PASSENGER", "seats", 1,
                "from", Map.of("regionId", r), "to", Map.of("regionId", r + 1)), user, 200).get("id").asLong();
        boolean matched = false;
        for (JsonNode n : call("GET", "/api/notifications", null, driver, 200).get("items")) {
            matched |= n.get("link").asText().equals("#/orders/" + onRoute);
        }
        assertThat(matched).isTrue();
        reminders.morningDigest();
        assertThat(call("PUT", "/api/users/me/settings", Map.of("routeOnly", false), driver, 200).get("routeOnly").asBoolean()).isFalse();

        // Hisob o'chirilsa — murojaatlari ham, ular haqida egaga kelgan bildirishnomalar ham (ism va matn bilan) o'chadi
        long ownerBefore = call("GET", "/api/notifications", null, owner, 200).get("total").asLong();
        call("POST", "/api/users/me/delete", Map.of("password", "secret123"), user, 200);
        assertThat(call("GET", "/api/admin/support?from=users", null, owner, 200).get("items").size()).isZero();
        boolean left = false;
        for (JsonNode n : call("GET", "/api/notifications", null, owner, 200).get("items")) {
            left |= n.get("title").asText().contains("Murojaat Qiluvchi");
        }
        assertThat(left).isFalse();
        assertThat(call("GET", "/api/notifications", null, owner, 200).get("total").asLong()).isEqualTo(ownerBefore - 5);
        assertThat(call("GET", "/api/admin/support?from=drivers", null, owner, 200).get("items").size()).isEqualTo(1);
    }
}
