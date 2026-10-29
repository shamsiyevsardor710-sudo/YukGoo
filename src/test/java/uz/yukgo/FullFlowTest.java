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
        JsonNode res = call("POST", "/api/auth/register/complete", Map.of(
                "requestId", requestId, "firstName", first, "lastName", last, "password", "secret123",
                "regionId", r, "districtId", firstDistrict(r), "village", "Bog'bon mahallasi", "house", "12-uy",
                "acceptTerms", acceptTerms), null, 200);
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

        // Haydovchi mashina qo'shadi
        call("PUT", "/api/users/me/vehicle", Map.of("brand", "Isuzu", "model", "NPR", "plateNumber", "01 a 123 bc",
                "bodyType", "TENT", "capacityKg", 5000), driver, 200);

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

        // Bozorda ko'rinadi (mehmon uchun ham)
        JsonNode market = call("GET", "/api/orders", null, null, 200);
        assertThat(market.get("total").asLong()).isGreaterThanOrEqualTo(1);
        // Begona odam topshirish kodini ko'rmaydi
        assertThat(call("GET", "/api/orders/" + orderId, null, driver, 200).has("deliveryCode")).isFalse();

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
        assertThat(call("GET", "/api/orders/" + orderId + "/messages", null, sender, 200).size()).isEqualTo(1);

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
                "bodyType", "TENT", "capacityKg", 5000), driver, 200);
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
}
