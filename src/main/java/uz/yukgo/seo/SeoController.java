package uz.yukgo.seo;

import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;
import uz.yukgo.channel.OrderCard;
import uz.yukgo.common.PageResponse;
import uz.yukgo.common.Phones;
import uz.yukgo.config.AppProperties;
import uz.yukgo.location.LocationService;
import uz.yukgo.location.LocationService.RegionDto;
import uz.yukgo.order.OrderDtos.OrderDto;
import uz.yukgo.order.OrderKind;
import uz.yukgo.order.OrderService;
import uz.yukgo.trip.TripService;
import uz.yukgo.trip.TripService.TripDto;

import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * Qidiruv tizimlari uchun yo'nalish sahifalari: «Toshkentdan Samarqandga yo'lovchi» deb qidirgan odam to'g'ridan-to'g'ri
 * shu yo'nalishdagi ochiq e'lonlar va reyslarni ko'radi (serverda tayyor HTML — Google JavaScript'siz ham o'qiydi).
 * /yo — barcha yo'nalishlar, /yo/toshkent-shahri/samarqand — bitta yo'nalish, /sitemap.xml va /robots.txt.
 * Faqat ochiq ma'lumot: telefon, ism va aniq manzil yo'q (mehmonga ko'rinadigan narsalar).
 */
@RestController
@RequiredArgsConstructor
public class SeoController {
    static final ZoneId TZ = ZoneId.of("Asia/Tashkent");
    static final int MAX_ITEMS = 15;
    private static final MediaType HTML = new MediaType("text", "html", java.nio.charset.StandardCharsets.UTF_8);
    private static final DateTimeFormatter HM = DateTimeFormatter.ofPattern("HH:mm");

    private final LocationService locations;
    private final OrderService orders;
    private final TripService trips;
    private final AppProperties props;

    // =====================================================================
    // Manzillar
    // =====================================================================

    /** "Samarqand viloyati" → "samarqand", "Toshkent shahri" → "toshkent-shahri", "Farg'ona viloyati" → "fargona" */
    static String slug(String name) {
        String s = name.toLowerCase(Locale.ROOT).replaceAll("['‘’ʻʼ`]", "").replace(" respublikasi", "");
        if (s.endsWith(" viloyati") && !s.startsWith("toshkent")) s = s.substring(0, s.length() - " viloyati".length());
        return s.replaceAll("[^a-z0-9]+", "-").replaceAll("(^-+|-+$)", "");
    }

    /** Sarlavha uchun qisqa nom: "Samarqand", "Toshkent shahri", "Toshkent viloyati", "Qoraqalpog'iston" */
    static String shortName(String name) {
        String s = name.replace(" Respublikasi", "");
        if (s.endsWith(" viloyati") && !s.startsWith("Toshkent")) s = s.substring(0, s.length() - " viloyati".length());
        return s;
    }

    private Map<String, RegionDto> bySlug() {
        Map<String, RegionDto> m = new LinkedHashMap<>();
        for (RegionDto r : locations.regions()) m.put(slug(r.name()), r);
        return m;
    }

    private String base() {
        String url = props.publicUrl() == null ? "" : props.publicUrl().trim();
        if (url.isEmpty()) url = ServletUriComponentsBuilder.fromCurrentContextPath().build().toUriString();
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    private static ResponseEntity<String> html(HttpStatus status, String body) {
        return ResponseEntity.status(status).contentType(HTML)
                .cacheControl(CacheControl.maxAge(Duration.ofMinutes(10)).cachePublic()).body(body);
    }

    // =====================================================================
    // Sahifalar
    // =====================================================================

    @GetMapping("/yo")
    public ResponseEntity<String> index() {
        Map<String, RegionDto> regions = bySlug();
        StringBuilder body = new StringBuilder();
        body.append("<h1>Yo'nalishlar: yo'lovchi, pochta va yuk</h1>")
                .append("<p class=\"lead\">Qayerdan va qayerga ketayotganingizni tanlang — shu yo'nalishdagi ochiq e'lonlar va ")
                .append("haydovchilar reyslari.</p>");
        for (Map.Entry<String, RegionDto> a : regions.entrySet()) {
            body.append("<section><h2>").append(esc(shortName(a.getValue().name()))).append("dan</h2><p class=\"links\">");
            for (Map.Entry<String, RegionDto> b : regions.entrySet()) {
                if (a.getKey().equals(b.getKey())) continue;
                body.append("<a href=\"/yo/").append(a.getKey()).append('/').append(b.getKey()).append("\">")
                        .append(esc(shortName(b.getValue().name()))).append("ga</a>");
            }
            body.append("</p></section>");
        }
        return html(HttpStatus.OK, page("Yo'nalishlar: yo'lovchi, pochta va yuk tashish | YukGo",
                "O'zbekiston bo'ylab viloyatdan viloyatga yo'lovchi, pochta va yuk: ochiq e'lonlar va haydovchilar reyslari.",
                base() + "/yo", body.toString()));
    }

    @GetMapping("/yo/{from}/{to}")
    public ResponseEntity<String> route(@PathVariable String from, @PathVariable String to) {
        Map<String, RegionDto> regions = bySlug();
        RegionDto a = regions.get(from), b = regions.get(to);
        if (a == null || b == null || a.id().equals(b.id())) {
            return html(HttpStatus.NOT_FOUND, page("Yo'nalish topilmadi | YukGo", "Bunday yo'nalish yo'q.", base() + "/yo",
                    "<h1>Yo'nalish topilmadi</h1><p class=\"lead\">Barcha yo'nalishlar ro'yxatidan tanlang.</p>"
                            + "<p><a class=\"btn primary\" href=\"/yo\">Yo'nalishlar</a></p>"));
        }
        String fromName = shortName(a.name()), toName = shortName(b.name());
        LocalDate today = LocalDate.now(TZ);

        List<OrderDto> open = new ArrayList<>();
        PageResponse<OrderDto> market = orders.market(new OrderService.MarketFilter(null, a.id(), null, b.id(), null,
                null, null, null, null, null), 0);
        for (OrderDto o : market.items()) {
            if (o.pickupDate() != null && o.pickupDate().isBefore(today)) continue;   // sanasi o'tganlar ko'rsatilmaydi
            open.add(o);
            if (open.size() == MAX_ITEMS) break;
        }
        List<TripDto> rides = trips.search(a.id(), null, b.id(), null, 0).items().stream().limit(MAX_ITEMS).toList();

        String q = "fr=" + a.id() + "&tr=" + b.id();
        StringBuilder body = new StringBuilder();
        body.append("<nav class=\"crumbs\"><a href=\"/\">YukGo</a> › <a href=\"/yo\">Yo'nalishlar</a> › ")
                .append(esc(fromName)).append(" → ").append(esc(toName)).append("</nav>");
        body.append("<h1>").append(esc(fromName)).append(" → ").append(esc(toName)).append(": yo'lovchi, pochta va yuk</h1>");
        body.append("<p class=\"lead\">").append(esc(fromName)).append("dan ").append(esc(toName))
                .append("ga ketadigan haydovchilar va shu yo'nalishdagi e'lonlar. E'lon bering — yo'nalishdagi haydovchilarga ")
                .append("darhol xabar boradi. Telefon raqamingiz haydovchi «Olaman» deguncha yopiq turadi.</p>");
        body.append("<div class=\"cta\">")
                .append("<a class=\"btn primary\" href=\"/#/new?k=PASSENGER&amp;").append(q.replace("&", "&amp;")).append("\">🧍 Yo'lovchi sifatida e'lon</a>")
                .append("<a class=\"btn\" href=\"/#/new?k=PARCEL&amp;").append(q.replace("&", "&amp;")).append("\">✉️ Pochta yuborish</a>")
                .append("<a class=\"btn\" href=\"/#/new?k=CARGO&amp;").append(q.replace("&", "&amp;")).append("\">📦 Yuk jo'natish</a>")
                .append("</div>");

        body.append("<section><h2>Ochiq e'lonlar").append(open.isEmpty() ? "" : " — " + open.size() + " ta").append("</h2>");
        if (open.isEmpty()) {
            body.append("<p class=\"muted\">Hozir bu yo'nalishda ochiq e'lon yo'q. Birinchi bo'lib e'lon bering — haydovchilarga xabar boradi.</p>");
        } else {
            body.append("<ul class=\"items\">");
            for (OrderDto o : open) body.append("<li><a href=\"/#/orders/").append(o.id()).append("\">").append(orderLine(o, today)).append("</a></li>");
            body.append("</ul>");
        }
        body.append("<p><a href=\"/#/?from=").append(a.id()).append("&amp;to=").append(b.id()).append("\">Barcha e'lonlar →</a></p></section>");

        body.append("<section><h2>Haydovchilar reyslari").append(rides.isEmpty() ? "" : " — " + rides.size() + " ta").append("</h2>");
        if (rides.isEmpty()) {
            body.append("<p class=\"muted\">Hozircha reys yo'q. Haydovchimisiz? Reys e'lon qiling — shu yo'nalishdagi yo'lovchi va ")
                    .append("yuk egalariga xabar boradi.</p>");
        } else {
            body.append("<ul class=\"items\">");
            for (TripDto t : rides) body.append("<li>").append(tripLine(t, today)).append("</li>");
            body.append("</ul>");
        }
        body.append("<p><a href=\"/#/trips?from=").append(a.id()).append("&amp;to=").append(b.id()).append("\">Barcha reyslar →</a></p></section>");

        body.append("<section class=\"how\"><h2>Qanday ishlaydi</h2><ol>")
                .append("<li><b>E'lon bering</b> — qayerdan, qayerga va qachon. 1 daqiqa.</li>")
                .append("<li><b>Haydovchi «Olaman» deydi</b> yoki narx taklif qiladi — sizga xabar keladi.</li>")
                .append("<li><b>Raqamlar ochiladi</b> — telefonda kelishib olasiz.</li></ol></section>");

        body.append("<section><h2>Boshqa yo'nalishlar</h2><p class=\"links\">")
                .append("<a href=\"/yo/").append(to).append('/').append(from).append("\">").append(esc(toName)).append(" → ")
                .append(esc(fromName)).append("</a>");
        for (Map.Entry<String, RegionDto> r : regions.entrySet()) {
            if (r.getKey().equals(from) || r.getKey().equals(to)) continue;
            body.append("<a href=\"/yo/").append(from).append('/').append(r.getKey()).append("\">").append(esc(fromName)).append(" → ")
                    .append(esc(shortName(r.getValue().name()))).append("</a>");
        }
        body.append("</p></section>");

        String title = fromName + " – " + toName + ": yo'lovchi, pochta, yuk tashish | YukGo";
        String desc = fromName + "dan " + toName + "ga yo'lovchi, pochta va yuk: ochiq e'lonlar va haydovchilar reyslari. "
                + "E'lon bering — yo'nalishdagi haydovchilarga darhol xabar boradi.";
        return html(HttpStatus.OK, page(title, desc, base() + "/yo/" + from + "/" + to, body.toString()));
    }

    /** «🧍 2 kishi · Chilonzor → Samarqand · Ertaga · 300 000 so'm» (mehmon ko'radigan ma'lumot) */
    static String orderLine(OrderDto o, LocalDate today) {
        OrderKind k = o.kind() == null ? OrderKind.CARGO : o.kind();
        String what = switch (k) {
            case PASSENGER -> "🧍 " + (o.seats() == null ? 1 : o.seats()) + " kishi";
            case PARCEL -> "✉️ Pochta";
            default -> "📦 " + cut(cleanTitle(o.title()), 60);
        };
        List<String> parts = new ArrayList<>();
        parts.add(esc(what));
        parts.add(esc(place(o.from().districtName(), o.from().regionName())) + " → " + esc(place(o.to().districtName(), o.to().regionName())));
        parts.add(o.pickupDate() == null ? "Sana kelishiladi" : esc(dayLabel(o.pickupDate(), today)));
        parts.add(o.price() != null && o.price() > 0 ? esc(OrderCard.money(o.price())) : "Narx kelishiladi");
        return String.join(" · ", parts);
    }

    /** «🚗 Chevrolet Cobalt · Ertaga 07:00 · 3 bo'sh o'rin · Yunusobod → Samarqand» */
    static String tripLine(TripDto t, LocalDate today) {
        List<String> parts = new ArrayList<>();
        String car = t.driver() != null && t.driver().vehicle() != null
                ? t.driver().vehicle().brand() + (t.driver().vehicle().model() != null ? " " + t.driver().vehicle().model() : "") : "Haydovchi";
        parts.add("🚗 " + esc(cut(car, 40)));
        if (t.daily()) parts.add("Har kuni" + (t.departAt() != null ? " " + t.departAt().format(HM) : ""));
        else if (t.departAt() != null) parts.add(esc(dayLabel(t.departAt().toLocalDate(), today)) + " " + t.departAt().format(HM));
        if (t.seats() != null && t.seats() > 0) parts.add(t.seats() + " bo'sh o'rin");
        parts.add(esc(place(t.fromDistrict(), t.fromRegion())) + " → " + esc(place(t.toDistrict(), t.toRegion())));
        return String.join(" · ", parts);
    }

    private static String dayLabel(LocalDate d, LocalDate today) {
        if (d.equals(today)) return "Bugun";
        if (d.equals(today.plusDays(1))) return "Ertaga";
        return d.format(DateTimeFormatter.ofPattern("dd.MM"));
    }

    private static String place(String district, String region) {
        return district != null && !district.isBlank() ? district : region == null ? "" : shortName(region);
    }

    /** Begona ko'radigan sarlavhadan yashirilgan raqam/kontakt belgilari ham olib tashlanadi */
    static String cleanTitle(String t) {
        if (t == null) return "Yuk";
        String s = OrderCard.publicTitle(t.replace(Phones.HIDDEN_IN_TEXT, " ").replace(Phones.HIDDEN_CONTACT, " "));
        return s.isBlank() ? "Yuk" : s;
    }

    private static String cut(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }

    static String esc(String s) {
        if (s == null) return "";
        StringBuilder b = new StringBuilder(s.length() + 16);
        for (char c : s.toCharArray()) {
            switch (c) {
                case '&' -> b.append("&amp;");
                case '<' -> b.append("&lt;");
                case '>' -> b.append("&gt;");
                case '"' -> b.append("&quot;");
                case '\'' -> b.append("&#39;");
                default -> b.append(c);
            }
        }
        return b.toString();
    }

    /** Umumiy sahifa qolipi: yengil, JavaScript'siz, kunduzgi va kechki rejim */
    static String page(String title, String description, String canonical, String body) {
        return "<!doctype html><html lang=\"uz\"><head><meta charset=\"utf-8\">"
                + "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">"
                + "<title>" + esc(title) + "</title><meta name=\"description\" content=\"" + esc(description) + "\">"
                + "<link rel=\"canonical\" href=\"" + esc(canonical) + "\">"
                + "<meta property=\"og:title\" content=\"" + esc(title) + "\"><meta property=\"og:description\" content=\""
                + esc(description) + "\"><meta property=\"og:type\" content=\"website\"><meta property=\"og:url\" content=\"" + esc(canonical) + "\">"
                + "<meta name=\"theme-color\" content=\"#1F2A30\"><link rel=\"apple-touch-icon\" href=\"/icons/apple-touch-icon.png\">"
                + "<link rel=\"icon\" href=\"/icons/icon-192.png\">"
                + "<style>" + CSS + "</style></head><body>"
                + "<header class=\"top\"><a class=\"brand\" href=\"/\"><span class=\"dot\"></span>YukGo</a>"
                + "<a class=\"btn primary small\" href=\"/#/new\">E'lon berish</a></header>"
                + "<main>" + body + "</main>"
                + "<footer><a href=\"/\">YukGo</a> · <a href=\"/yo\">Yo'nalishlar</a> · <a href=\"/privacy.html\">Maxfiylik</a> · "
                + "<a href=\"/terms.html\">Foydalanish shartlari</a></footer></body></html>";
    }

    private static final String CSS = ":root{--bg:#EEF1F0;--card:#fff;--ink:#1F2A30;--muted:#66757D;--line:#D5DCDE;--sig:#F2B705;--route:#1D5FA8}"
            + "@media (prefers-color-scheme:dark){:root{--bg:#12181B;--card:#1A2227;--ink:#E7ECEE;--muted:#8E9CA3;--line:#2D3940;--route:#6FA6E6}}"
            + "*{box-sizing:border-box}body{margin:0;background:var(--bg);color:var(--ink);font:16px/1.55 system-ui,-apple-system,'Segoe UI',Roboto,sans-serif}"
            + "a{color:var(--route);text-decoration:none}a:hover{text-decoration:underline}"
            + ".top{display:flex;justify-content:space-between;align-items:center;gap:12px;padding:12px 16px;background:#1F2A30}"
            + ".brand{color:#fff;font-weight:800;font-size:20px;display:flex;align-items:center;gap:8px}.dot{width:12px;height:12px;border-radius:50%;background:var(--sig)}"
            + "main{max-width:860px;margin:0 auto;padding:20px 16px 40px}h1{font-size:28px;line-height:1.2;margin:8px 0 10px}h2{font-size:20px;margin:0 0 10px}"
            + ".lead{color:var(--muted);margin:0 0 18px}.crumbs{font-size:14px;color:var(--muted)}"
            + "section{background:var(--card);border:1px solid var(--line);border-radius:16px;padding:16px 18px;margin:14px 0}"
            + ".cta{display:flex;flex-wrap:wrap;gap:10px;margin:0 0 6px}.btn{display:inline-block;padding:11px 16px;border-radius:12px;border:1px solid var(--line);"
            + "background:var(--card);color:var(--ink);font-weight:600}.btn.primary{background:var(--sig);border-color:var(--sig);color:#2A2100}"
            + ".btn.small{padding:7px 12px;font-size:14px}.btn:hover{text-decoration:none;filter:brightness(.97)}"
            + ".items{list-style:none;margin:0;padding:0}.items li{padding:10px 0;border-bottom:1px solid var(--line)}.items li:last-child{border-bottom:0}"
            + ".items a{color:var(--ink)}.muted{color:var(--muted)}.links{display:flex;flex-wrap:wrap;gap:8px 14px;margin:0}"
            + ".how ol{margin:0;padding-left:20px}.how li{margin:4px 0}footer{text-align:center;color:var(--muted);font-size:14px;padding:0 16px 30px}";

    // =====================================================================
    // Qidiruv tizimlari uchun
    // =====================================================================

    @GetMapping(value = "/sitemap.xml", produces = "application/xml;charset=UTF-8")
    public ResponseEntity<String> sitemap() {
        String base = base();
        String today = LocalDate.now(TZ).toString();
        StringBuilder sb = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                + "<urlset xmlns=\"http://www.sitemaps.org/schemas/sitemap/0.9\">\n");
        url(sb, base + "/", today, "daily");
        url(sb, base + "/yo", today, "weekly");
        List<String> slugs = new ArrayList<>(bySlug().keySet());
        for (String a : slugs) {
            for (String b : slugs) if (!a.equals(b)) url(sb, base + "/yo/" + a + "/" + b, today, "daily");
        }
        url(sb, base + "/privacy.html", null, "monthly");
        url(sb, base + "/terms.html", null, "monthly");
        sb.append("</urlset>\n");
        return ResponseEntity.ok().cacheControl(CacheControl.maxAge(Duration.ofHours(6)).cachePublic()).body(sb.toString());
    }

    private static void url(StringBuilder sb, String loc, String lastmod, String freq) {
        sb.append("  <url><loc>").append(esc(loc)).append("</loc>");
        if (lastmod != null) sb.append("<lastmod>").append(lastmod).append("</lastmod>");
        sb.append("<changefreq>").append(freq).append("</changefreq></url>\n");
    }

    @GetMapping(value = "/robots.txt", produces = "text/plain;charset=UTF-8")
    public ResponseEntity<String> robots() {
        String body = "User-agent: *\nAllow: /\nDisallow: /api/\nDisallow: /uploads/\n\nSitemap: " + base() + "/sitemap.xml\n";
        return ResponseEntity.ok().cacheControl(CacheControl.maxAge(Duration.ofHours(6)).cachePublic()).body(body);
    }
}
