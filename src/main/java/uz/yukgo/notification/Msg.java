package uz.yukgo.notification;

import uz.yukgo.telegram.TelegramClient;

import java.util.ArrayList;
import java.util.List;

/**
 * Bildirishnoma matni — bir joyda tuziladi, uch xil ko'rinishda chiqadi:
 * <ul>
 *   <li>sayt ichida 🔔 va telefonga push — oddiy matn (har bir qator alohida);</li>
 *   <li>Telegram — HTML: qalin sarlavha, bo'sh qator, belgili qatorlar (muhim qiymatlar qalin), oxirida qiya izoh;</li>
 *   <li>ixtiyoriy rasm ({@link TgCard}) — Telegramda xabar rasm bilan keladi, matni rasm ostida.</li>
 * </ul>
 * Misol: {@code Msg.of("✅ Yo'lovchi sizniki!").strong("📍", "Kosonsoy → Toshkent").kv("📞", "Telefon", "+998…")}
 */
public final class Msg {
    /** Qator: belgi (emoji), ixtiyoriy yorliq va qiymat; strong — qiymat qalin */
    record Line(String emoji, String label, String value, boolean strong) {}

    /** Telegram tugmasi: matni va sayt ichidagi havola ("#/trips/5/plan") */
    public record Button(String text, String link) {}

    private final String title;
    private final List<Line> lines = new ArrayList<>();
    private final List<String> paragraphs = new ArrayList<>();
    private String intro;
    private String note;
    private TgCard card;
    private String buttonText = "Ochish";
    private final List<Button> extra = new ArrayList<>();

    private Msg(String title) {
        this.title = title == null ? "" : title;
    }

    public static Msg of(String title) {
        return new Msg(title);
    }

    /** Eski uslub: sarlavha va bitta matn (sayt ichida ham, Telegramda ham shu matn) */
    public static Msg of(String title, String body) {
        Msg m = new Msg(title);
        if (body != null && !body.isBlank()) m.paragraphs.add(body.trim());
        return m;
    }

    /** "📍 Kosonsoy → Toshkent" */
    public Msg line(String emoji, String text) {
        if (text != null && !text.isBlank()) lines.add(new Line(emoji, null, text.trim(), false));
        return this;
    }

    /** "📍 <b>Kosonsoy → Toshkent</b>" — qiymat Telegramda qalin */
    public Msg strong(String emoji, String text) {
        if (text != null && !text.isBlank()) lines.add(new Line(emoji, null, text.trim(), true));
        return this;
    }

    /** "👤 Yo'lovchi: <b>Ali Valiyev</b>" */
    public Msg kv(String emoji, String label, String value) {
        if (value != null && !value.isBlank()) lines.add(new Line(emoji, label, value.trim(), true));
        return this;
    }

    /** Sarlavhadan keyingi qisqa kirish so'zi — qatorlardan oldin: "Bugungi reysingizga mos e'lonlar bor 👇" */
    public Msg intro(String text) {
        this.intro = text == null || text.isBlank() ? null : text.trim();
        return this;
    }

    /** Qatorlardan keyin alohida xatboshi (masalan, maslahat) */
    public Msg para(String text) {
        if (text != null && !text.isBlank()) paragraphs.add(text.trim());
        return this;
    }

    /** Oxirgi qiya izoh: "Narxni telefonda kelishib oling" */
    public Msg note(String text) {
        this.note = text == null || text.isBlank() ? null : text.trim();
        return this;
    }

    public Msg card(TgCard card) {
        this.card = card;
        return this;
    }

    /** Asosiy tugma matni (havola — bildirishnomaning o'zi) */
    public Msg button(String text) {
        if (text != null && !text.isBlank()) this.buttonText = text;
        return this;
    }

    /** Qo'shimcha tugma (Telegramda), masalan «🧩 Reysni yig'ish» */
    public Msg extra(String text, String link) {
        if (text != null && link != null) extra.add(new Button(text, link));
        return this;
    }

    public String title() {
        return title;
    }

    public TgCard card() {
        return card;
    }

    public String buttonText() {
        return buttonText;
    }

    public List<Button> extraButtons() {
        return List.copyOf(extra);
    }

    /** Sayt ichida 🔔 va push uchun: har bir qator alohida, belgilari bilan */
    public String plain() {
        List<String> out = new ArrayList<>();
        if (intro != null) out.add(intro);
        for (Line l : lines) out.add(prefix(l.emoji()) + (l.label() != null ? l.label() + ": " : "") + l.value());
        out.addAll(paragraphs);
        if (note != null) out.add(note);
        return String.join("\n", out);
    }

    /** Telegram HTML (parse_mode=HTML) */
    public String html() {
        StringBuilder sb = new StringBuilder("<b>").append(TelegramClient.esc(title)).append("</b>");
        if (intro != null) sb.append("\n").append(TelegramClient.esc(intro));
        if (!lines.isEmpty()) {
            sb.append("\n");
            for (Line l : lines) {
                sb.append("\n").append(prefix(l.emoji()));
                if (l.label() != null) sb.append(TelegramClient.esc(l.label())).append(": ");
                String v = TelegramClient.esc(l.value());
                sb.append(l.strong() ? "<b>" + v + "</b>" : v);
            }
        }
        for (String p : paragraphs) sb.append("\n\n").append(TelegramClient.esc(p));
        if (note != null) sb.append("\n\n<i>").append(TelegramClient.esc(note)).append("</i>");
        return sb.toString();
    }

    private static String prefix(String emoji) {
        return emoji == null || emoji.isBlank() ? "" : emoji + " ";
    }
}
