package uz.yukgo.telegram;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Telegram sendPhoto: rasmni yuklash (multipart/form-data) yoki avval yuklangan rasmni file_id bilan yuborish.
 * JDK HttpClient bilan — qo'shimcha kutubxonasiz va alohida sinash oson. Manzilda bot tokeni bor: xatolarni logga
 * faqat {@link TelegramClient#safe(Throwable)} orqali yozing.
 */
final class TelegramPhotoApi {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Duration TIMEOUT = Duration.ofSeconds(40);

    /** "https://api.telegram.org/bot&lt;token&gt;" */
    private final String base;
    private final HttpClient http;

    TelegramPhotoApi(String base) {
        this(base, HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build());
    }

    TelegramPhotoApi(String base, HttpClient http) {
        this.base = base;
        this.http = http;
    }

    /** ok — yuborildi; fileId — Telegramdagi rasm ID si (keyingi yuborishlar uchun); status — HTTP holati; body — javob matni */
    record Result(boolean ok, String fileId, int status, String body) {}

    Result upload(long chatId, byte[] png, String caption, Object replyMarkup) throws IOException, InterruptedException {
        String boundary = "----yukgo" + UUID.randomUUID().toString().replace("-", "");
        ByteArrayOutputStream out = new ByteArrayOutputStream(png.length + 4096);
        field(out, boundary, "chat_id", String.valueOf(chatId));
        if (caption != null) {
            field(out, boundary, "caption", caption);
            field(out, boundary, "parse_mode", "HTML");
        }
        if (replyMarkup != null) field(out, boundary, "reply_markup", JSON.writeValueAsString(replyMarkup));
        out.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"photo\"; filename=\"yukgo.png\"\r\n"
                + "Content-Type: image/png\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        out.write(png);
        out.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        HttpRequest req = HttpRequest.newBuilder(URI.create(base + "/sendPhoto"))
                .timeout(TIMEOUT)
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofByteArray(out.toByteArray()))
                .build();
        return result(http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)));
    }

    Result byId(long chatId, String fileId, String caption, Object replyMarkup) throws IOException, InterruptedException {
        Map<String, Object> body = new HashMap<>();
        body.put("chat_id", chatId);
        body.put("photo", fileId);
        if (caption != null) {
            body.put("caption", caption);
            body.put("parse_mode", "HTML");
        }
        if (replyMarkup != null) body.put("reply_markup", replyMarkup);
        HttpRequest req = HttpRequest.newBuilder(URI.create(base + "/sendPhoto"))
                .timeout(TIMEOUT)
                .header("Content-Type", "application/json; charset=UTF-8")
                .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body), StandardCharsets.UTF_8))
                .build();
        Result r = result(http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)));
        return r.ok() && r.fileId() == null ? new Result(true, fileId, r.status(), r.body()) : r;
    }

    private static void field(ByteArrayOutputStream out, String boundary, String name, String value) throws IOException {
        out.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"" + name + "\"\r\n"
                + "Content-Type: text/plain; charset=UTF-8\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        out.write(value.getBytes(StandardCharsets.UTF_8));
        out.write("\r\n".getBytes(StandardCharsets.UTF_8));
    }

    /** Javobdan eng katta o'lchamdagi rasmning file_id si */
    static Result result(HttpResponse<String> res) {
        String body = res.body() == null ? "" : res.body();
        if (res.statusCode() / 100 != 2) return new Result(false, null, res.statusCode(), body);
        try {
            JsonNode n = JSON.readTree(body);
            if (!n.path("ok").asBoolean()) return new Result(false, null, res.statusCode(), body);
            JsonNode photos = n.path("result").path("photo");
            String id = photos.isArray() && !photos.isEmpty() ? photos.get(photos.size() - 1).path("file_id").asText(null) : null;
            return new Result(true, id, res.statusCode(), body);
        } catch (IOException e) {
            return new Result(true, null, res.statusCode(), body);
        }
    }
}
