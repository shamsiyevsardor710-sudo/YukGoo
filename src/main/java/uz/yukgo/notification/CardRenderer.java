package uz.yukgo.notification;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.geom.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * {@link TgCard} dan PNG rasm (1080×810) chizadi: to'q fon, mavzu rangidagi nur, YukGo belgisi, sayt shriftlari
 * (Unbounded va Onest — resources/fonts). Rasmlarda emoji yo'q (Java ularni chiza olmaydi) — belgilar chiziladi.
 * Shrift yuklanmasa yoki chizishda xato bo'lsa — {@link #available()} false bo'ladi, xabarlar rasmsiz (matn) ketadi.
 */
@Slf4j
@Component
public class CardRenderer {
    public static final int W = 1080, H = 810;
    static final int PAD = 64;

    static final Color BG_TOP = new Color(0x13, 0x1F, 0x24), BG_BOTTOM = new Color(0x0A, 0x10, 0x13);
    static final Color TEXT = new Color(0xF4, 0xF7, 0xF8), MUTED = new Color(0x93, 0xA7, 0xAF);
    static final Color SURFACE = new Color(0x1B, 0x29, 0x2F), LINE = new Color(0x2A, 0x3D, 0x45);
    static final Color YELLOW = new Color(0xF2, 0xB7, 0x05), INK = new Color(0x0C, 0x11, 0x13);
    static final Color[] CONFETTI = {
            new Color(0xF2, 0xB7, 0x05), new Color(0x3D, 0xDC, 0x97), new Color(0xFF, 0x6B, 0x6B),
            new Color(0xB4, 0x7C, 0xFF), new Color(0x4F, 0xA3, 0xFF), new Color(0xFF, 0x9F, 0x43)};

    record Fonts(Font display, Font bold, Font semi, Font medium) {}

    private volatile Fonts fonts;
    private volatile boolean broken;
    /** Ketma-ket chizilmagan rasmlar: bir martalik xato (masalan, xotira) rasmlarni butunlay o'chirmasin */
    private final java.util.concurrent.atomic.AtomicInteger failures = new java.util.concurrent.atomic.AtomicInteger();
    static final int MAX_FAILURES = 5;
    /** Pastdagi shior (tagline) yuqori cheti: tabletkalar va kataklar shundan pastga tushmasin */
    static final float TAG_TOP = H - 50 - 30;

    /** Rasm chizish mumkinmi (shriftlar yuklandi, avval xato bo'lmagan) */
    public boolean available() {
        return !broken && fonts() != null;
    }

    private Fonts fonts() {
        Fonts f = fonts;
        if (f != null || broken) return f;
        synchronized (this) {
            if (fonts != null || broken) return fonts;
            try {
                fonts = new Fonts(load("Unbounded-ExtraBold"), load("Onest-Bold"), load("Onest-SemiBold"), load("Onest-Medium"));
                FALLBACK = fonts.bold();   // sarlavha shriftida harf bo'lmasa (masalan, kirillcha Қ, Ғ, Ҳ) — Onest
            } catch (Throwable e) {
                broken = true;
                log.warn("Telegram rasmlari o'chirildi — shrift yuklanmadi: {}", e.toString());
            }
            return fonts;
        }
    }

    private static Font load(String name) throws IOException, FontFormatException {
        try (InputStream in = CardRenderer.class.getResourceAsStream("/fonts/" + name + ".ttf")) {
            if (in == null) throw new IOException("/fonts/" + name + ".ttf topilmadi");
            return Font.createFont(Font.TRUETYPE_FONT, in);
        }
    }

    static Color accent(TgCard.Theme t) {
        return switch (t) {
            case PASSENGER -> new Color(0x4F, 0xA3, 0xFF);
            case PARCEL -> new Color(0xB4, 0x7C, 0xFF);
            case CARGO -> new Color(0xFF, 0x9F, 0x43);
            case SUCCESS -> new Color(0x3D, 0xDC, 0x97);
            case MORNING -> new Color(0xFF, 0xC9, 0x3C);
            case TRIP -> YELLOW;
        };
    }

    /** PNG baytlari. Xato bo'lsa — IOException (chaqiruvchi matnli xabarga o'tadi) */
    public byte[] render(TgCard c) throws IOException {
        Fonts f = fonts();
        if (f == null) throw new IOException("Shriftlar yo'q");
        try {
            BufferedImage img = new BufferedImage(W, H, BufferedImage.TYPE_INT_RGB);
            paint(img, c, f);
            ByteArrayOutputStream out = new ByteArrayOutputStream(160_000);
            ImageIO.write(img, "png", out);
            failures.set(0);
            return out.toByteArray();
        } catch (IOException e) {
            throw e;
        } catch (Throwable e) {
            // Serverda grafik kutubxonasi yo'q (UnsatisfiedLinkError, AWTError) yoki ketma-ket ko'p xato — boshqa urinmaymiz,
            // xabarlar matn bo'lib ketadi. Bir martalik xato (masalan, xotira yetmadi) — faqat shu xabar matn bilan
            if (e instanceof LinkageError || e instanceof AWTError || failures.incrementAndGet() >= MAX_FAILURES) {
                broken = true;
                log.warn("Telegram rasmlari o'chirildi — chizib bo'lmadi: {}", e.toString());
            } else {
                log.warn("Telegram rasmi chizilmadi, xabar matn bilan ketadi: {}", e.toString());
            }
            throw new IOException(e.toString(), e);
        }
    }

    /**
     * Rasmni chizadi. Hammasi sig'masa (pastdagi shiorga tegsa): avval viloyat izohlarisiz, keyin sarlavha bir qatorda;
     * baribir sig'masa — shior chizilmaydi. Konfetti matn ostiga tushmasin: matn joylari ma'lum bo'lgach qayta chizamiz
     * (fon butun rasmni yopadi). @return {siqish darajasi, kontentning pastki cheti}
     */
    float[] paint(BufferedImage img, TgCard c, Fonts f) {
        Graphics2D g = img.createGraphics();
        try {
            hints(g);
            List<Rectangle2D> boxes = new ArrayList<>();
            int squeeze = 0;
            float bottom;
            BOXES.set(boxes);
            try {
                while (true) {
                    boxes.clear();
                    bottom = draw(g, c, f, null, squeeze);
                    if (bottom <= TAG_TOP - 8 || squeeze == 2) break;
                    squeeze++;
                }
            } finally {
                BOXES.remove();
            }
            if (c.hasConfetti()) draw(g, c, f, boxes, squeeze);
            return new float[]{squeeze, bottom};
        } finally {
            g.dispose();
        }
    }

    /** Sinov uchun: qaysi siqish darajasida sig'di va kontent qayergacha tushdi (shior — {@link #TAG_TOP} dan pastda) */
    float[] layout(TgCard c) throws IOException {
        Fonts f = fonts();
        if (f == null) throw new IOException("Shriftlar yo'q");
        return paint(new BufferedImage(W, H, BufferedImage.TYPE_INT_RGB), c, f);
    }

    static void hints(Graphics2D g) {
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
        g.setRenderingHint(RenderingHints.KEY_COLOR_RENDERING, RenderingHints.VALUE_COLOR_RENDER_QUALITY);
    }

    // =====================================================================
    // Joylashuv
    // =====================================================================

    /**
     * @param confettiAvoid null — konfettisiz; aks holda konfetti shu (matn) joylardan tashqarida
     * @param squeeze       0 — odatiy; 1 — yo'nalishda viloyat izohlarisiz; 2 — sarlavha ham bir qatorda
     * @return kontentning pastki cheti (shior bundan pastda)
     */
    private float draw(Graphics2D g, TgCard c, Fonts f, List<Rectangle2D> confettiAvoid, int squeeze) {
        Color accent = accent(c.theme());
        background(g, accent);
        if (confettiAvoid != null) confetti(g, c.key(), confettiAvoid);
        road(g);

        // Yuqori qator: belgi va yorliq
        logo(g, f, PAD, 72);
        if (c.badge() != null && !c.badge().isBlank()) badge(g, f, c.badge(), accent, W - PAD, 66);

        // Sarlavha (2 qatorgacha)
        float y = 176;
        if (c.title() != null && !c.title().isBlank()) {
            List<String> lines = new ArrayList<>();
            Font tf = wrap(c.title(), f.display(), 60f, 44f, W - PAD * 2f, squeeze >= 2 ? 1 : 2, lines);
            FontMetrics fm = g.getFontMetrics(tf);
            for (String line : lines) {
                y += fm.getAscent();
                text(g, line, tf, PAD, y, TEXT);
                y += fm.getDescent() + 4;
            }
            y += 28;
        }

        // Yo'nalish: ● Qayerdan  ┊  ○ Qayerga
        boolean seatsRow = c.seats() != null;
        boolean hasRoute = c.from() != null && c.to() != null;
        if (hasRoute) y = route(g, f, c, y, seatsRow, squeeze >= 1);

        // Katta raqamli kataklar (yo'nalishsiz kartada — masalan, ertalabki xulosa)
        if (!c.stats().isEmpty()) y = stats(g, f, c.stats(), accent, y + 6);

        // O'rinlar (reysga yig'ish)
        if (seatsRow) y = seats(g, f, c.seats(), accent, y + 6);

        // Tabletkalar: sana, kishilar, narx... (yo'nalishli kartada — pastroqda, bir tekis)
        float bottom = y;
        if (!c.chips().isEmpty()) bottom = chips(g, f, c.chips(), accent, hasRoute ? Math.max(y + 8, 560) : y + 10, H - 132);

        footer(g, f, c, accent, bottom);
        return bottom;
    }

    private static void background(Graphics2D g, Color accent) {
        g.setPaint(new GradientPaint(0, 0, BG_TOP, 0, H, BG_BOTTOM));
        g.fillRect(0, 0, W, H);
        // Mavzu rangidagi yumshoq nur (o'ng yuqorida)
        float r = 620;
        RadialGradientPaint glow = new RadialGradientPaint(new Point2D.Float(W - 80, 30), r,
                new float[]{0f, 1f}, new Color[]{alpha(accent, 70), alpha(accent, 0)});
        g.setPaint(glow);
        g.fill(new Ellipse2D.Float(W - 80 - r, 30 - r, r * 2, r * 2));
        // Pastda chapda — sariq nur (YukGo)
        RadialGradientPaint glow2 = new RadialGradientPaint(new Point2D.Float(40, H + 40), 520,
                new float[]{0f, 1f}, new Color[]{alpha(YELLOW, 34), alpha(YELLOW, 0)});
        g.setPaint(glow2);
        g.fill(new Ellipse2D.Float(40 - 520, H + 40 - 520, 1040, 1040));
    }

    /** Pastki o'ng burchakda yo'l (sayt bosh sahifasidagidek): to'q tasma va sariq uzuq chiziq */
    private static void road(Graphics2D g) {
        Path2D p = new Path2D.Float();
        p.moveTo(W * 0.64, H + 30);
        p.curveTo(W * 0.78, H * 0.84, W * 0.86, H * 0.95, W + 40, H * 0.66);
        g.setColor(new Color(0x18, 0x25, 0x2B));
        g.setStroke(new BasicStroke(54, BasicStroke.CAP_BUTT, BasicStroke.JOIN_ROUND));
        g.draw(p);
        g.setColor(alpha(YELLOW, 150));
        g.setStroke(new BasicStroke(5, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND, 10, new float[]{22, 18}, 0));
        g.draw(p);
    }

    private static void confetti(Graphics2D g, String seed, List<Rectangle2D> avoid) {
        Random rnd = new Random(seed.hashCode());
        AffineTransform keep = g.getTransform();
        for (int i = 0; i < 150; i++) {
            float x = rnd.nextFloat() * W, yy = 16 + rnd.nextFloat() * (H * 0.66f);
            // Matn ustiga tushmasin: faqat eng yuqori tasma (belgi va yorliqdan tashqari) va o'ng tomon
            boolean topBand = yy < 150 && x > 360 && x < W - 330;
            boolean rightSide = x > W * 0.70f && yy > 150;
            if (!topBand && !rightSide) continue;
            if (near(avoid, x, yy, 22)) continue;
            Color col = CONFETTI[rnd.nextInt(CONFETTI.length)];
            g.setColor(alpha(col, 120 + rnd.nextInt(110)));
            g.translate(x, yy);
            g.rotate(rnd.nextFloat() * Math.PI);
            float k = 0.8f + rnd.nextFloat() * 0.7f;
            if (rnd.nextInt(3) == 0) g.fill(new Ellipse2D.Float(-5 * k, -5 * k, 10 * k, 10 * k));
            else g.fill(new RoundRectangle2D.Float(-9 * k, -3.5f * k, 18 * k, 7 * k, 3, 3));
            g.setTransform(keep);
        }
    }

    private static void logo(Graphics2D g, Fonts f, float x, float top) {
        // Ikki nuqta va uzuq chiziq — saytdagi belgi
        g.setColor(YELLOW);
        g.fill(new Ellipse2D.Float(x, top + 6, 30, 30));
        g.setColor(TEXT);
        g.fill(new Ellipse2D.Float(x + 40, top, 30, 30));
        g.setColor(YELLOW);
        for (int i = 0; i < 3; i++) g.fill(new RoundRectangle2D.Float(x + 2 + i * 24, top + 44, 16, 6, 4, 4));
        text(g, "YukGo", f.display().deriveFont(38f), x + 88, top + 38, TEXT);
    }

    private static void badge(Graphics2D g, Fonts f, String label, Color accent, float right, float top) {
        Font bf = f.bold().deriveFont(26f);
        String s = label.toUpperCase(java.util.Locale.ROOT);
        FontMetrics fm = g.getFontMetrics(bf);
        float w = fm.stringWidth(s) + 44, h = 52;
        g.setColor(accent);
        g.fill(new RoundRectangle2D.Float(right - w, top, w, h, h, h));
        text(g, s, bf, right - w + 22, top + (h + fm.getAscent() - fm.getDescent()) / 2f, INK);
    }

    private static float route(Graphics2D g, Fonts f, TgCard c, float y, boolean compact, boolean noSubs) {
        String fromSub = noSubs ? null : c.fromSub(), toSub = noSubs ? null : c.toSub();
        float maxW = W - PAD * 2f - 70;
        Font main = f.bold().deriveFont(compact ? 50f : 56f), sub = f.medium().deriveFont(30f);
        FontMetrics mm = g.getFontMetrics(main), sm = g.getFontMetrics(sub);
        float gap = compact ? 104 : 118;
        float fromY = y + mm.getAscent() * 0.72f, toY = fromY + gap + (fromSub != null ? 22 : 0);
        float rail = PAD + 22, tx = PAD + 70;

        // Rels: sariq to'la nuqta → uzuq chiziq → oq halqa
        g.setColor(alpha(YELLOW, 200));
        g.setStroke(new BasicStroke(6, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND, 10, new float[]{12, 12}, 0));
        g.draw(new Line2D.Float(rail, fromY + 6, rail, toY - 40));
        g.setColor(YELLOW);
        g.fill(new Ellipse2D.Float(rail - 17, fromY - 34, 34, 34));
        g.setColor(TEXT);
        g.setStroke(new BasicStroke(7));
        g.draw(new Ellipse2D.Float(rail - 14, toY - 31, 28, 28));

        fitText(g, c.from(), main, tx, fromY, maxW, 36f, TEXT);
        if (fromSub != null) fitText(g, fromSub, sub, tx, fromY + sm.getAscent() + 12, maxW, 24f, MUTED);
        fitText(g, c.to(), main, tx, toY, maxW, 36f, TEXT);
        float end = toY + mm.getDescent();
        if (toSub != null) {
            fitText(g, toSub, sub, tx, toY + sm.getAscent() + 12, maxW, 24f, MUTED);
            end = toY + sm.getAscent() + 12 + sm.getDescent();
        }
        return end + 26;
    }

    /** «O'rinlar» qatori: mashinadagilar — oq, tavsiya — mavzu rangida, bo'shlari — kulrang chiziq */
    private static float seats(Graphics2D g, Fonts f, TgCard.Seats s, Color accent, float y) {
        Font lf = f.semi().deriveFont(30f);
        FontMetrics fm = g.getFontMetrics(lf);
        int used = Math.min(s.capacity(), s.taken() + s.suggested());
        String label = "O'rinlar: " + used + " / " + s.capacity();
        float x = PAD;
        text(g, label, lf, x, y + fm.getAscent(), MUTED);
        float sx = x + fm.stringWidth(label) + 28, size = 46, step = 58;
        float top = y + (fm.getAscent() - size) / 2f + 6;
        for (int i = 0; i < s.capacity(); i++) {
            float px = sx + i * step;
            if (px + size > W - PAD) break;
            Color col = i < s.taken() ? TEXT : i < s.taken() + s.suggested() ? accent : null;
            person(g, px, top, size, col);
        }
        return y + Math.max(fm.getHeight(), size) + 18;
    }

    private static void person(Graphics2D g, float x, float y, float s, Color fill) {
        Shape head = new Ellipse2D.Float(x + s * 0.32f, y, s * 0.36f, s * 0.36f);
        Shape body = new RoundRectangle2D.Float(x + s * 0.12f, y + s * 0.44f, s * 0.76f, s * 0.56f, s * 0.5f, s * 0.5f);
        if (fill != null) {
            g.setColor(fill);
            g.fill(head);
            g.fill(body);
        } else {
            g.setColor(new Color(0x45, 0x5A, 0x63));
            g.setStroke(new BasicStroke(3.5f));
            g.draw(head);
            g.draw(body);
        }
    }

    /** @return chizilgan tabletkalarning pastki cheti */
    private static float chips(Graphics2D g, Fonts f, List<TgCard.Chip> chips, Color accent, float y, float maxY) {
        float gap = 16, limit = W - PAD * 2f;
        // Hammasi bir qatorga sig'sin: avval 34px, sig'masa — 26px gacha kichraytiriladi; baribir sig'masa — ikkinchi qator
        float size = 34;
        for (; size > 26; size -= 2) {
            if (chipsWidth(g, f.semi().deriveFont(size), chips, gap) <= limit) break;
        }
        Font cf = f.semi().deriveFont(size);
        FontMetrics fm = g.getFontMetrics(cf);
        float h = Math.round(size * 2.3f), icon = Math.round(size * 1.06f), x = PAD;
        for (TgCard.Chip ch : chips) {
            String t = ch.text();
            float w = chipWidth(fm, t, icon);
            if (w > limit) { t = ellipsize(t, fm, limit - (w - fm.stringWidth(t))); w = chipWidth(fm, t, icon); }
            if (x + w > W - PAD + 0.5f) {   // keyingi qatorga
                x = PAD;
                y += h + gap;
                if (y + h > maxY + 10) return y - gap;
            }
            g.setColor(SURFACE);
            g.fill(new RoundRectangle2D.Float(x, y, w, h, h, h));
            g.setColor(LINE);
            g.setStroke(new BasicStroke(2));
            g.draw(new RoundRectangle2D.Float(x, y, w, h, h, h));
            float pad = h * 0.33f;
            icon(g, ch.icon(), x + pad, y + (h - icon) / 2f, icon, accent);
            text(g, t, cf, x + pad + icon + 14, y + (h + fm.getAscent() - fm.getDescent()) / 2f, TEXT);
            x += w + gap;
        }
        return y + h;
    }

    private static float chipWidth(FontMetrics fm, String t, float icon) {
        float pad = Math.round(fm.getFont().getSize2D() * 2.3f) * 0.33f;
        return pad + icon + 14 + fm.stringWidth(t) + pad + 6;
    }

    private static float chipsWidth(Graphics2D g, Font cf, List<TgCard.Chip> chips, float gap) {
        FontMetrics fm = g.getFontMetrics(cf);
        float icon = Math.round(cf.getSize2D() * 1.06f), sum = 0;
        for (TgCard.Chip ch : chips) sum += chipWidth(fm, ch.text(), icon) + gap;
        return sum - gap;
    }

    /** Katta raqamli kataklar: raqam (Unbounded), ostida yorliq, burchakda belgi */
    private static float stats(Graphics2D g, Fonts f, List<TgCard.Stat> stats, Color accent, float y) {
        int n = stats.size();
        float gap = 20, w = (W - PAD * 2f - gap * (n - 1)) / n, h = 196;
        Font vf = f.display().deriveFont(76f), lf = f.medium().deriveFont(32f);
        FontMetrics vm = g.getFontMetrics(vf);
        for (int i = 0; i < n; i++) {
            TgCard.Stat s = stats.get(i);
            float x = PAD + i * (w + gap);
            g.setColor(SURFACE);
            g.fill(new RoundRectangle2D.Float(x, y, w, h, 40, 40));
            g.setColor(LINE);
            g.setStroke(new BasicStroke(2));
            g.draw(new RoundRectangle2D.Float(x, y, w, h, 40, 40));
            icon(g, s.icon(), x + w - 64, y + 26, 38, accent);
            fitText(g, s.value(), vf, x + 28, y + 28 + vm.getAscent() * 0.86f, w - 100, 40f, TEXT);
            fitText(g, s.label(), lf, x + 28, y + h - 34, w - 48, 22f, MUTED);
        }
        return y + h + 22;
    }

    private static void footer(Graphics2D g, Fonts f, TgCard c, Color accent, float contentBottom) {
        float base = H - 50;
        if (contentBottom > TAG_TOP - 8) return;   // tabletkalar ustiga yozilmasin
        if (c.tagline() != null && !c.tagline().isBlank()) {
            Font tf = f.display().deriveFont(30f);
            fitText(g, c.tagline(), tf, PAD, base, W * 0.56f, 22f, accent);
        }
    }

    // =====================================================================
    // Belgilar (vektor)
    // =====================================================================

    static void icon(Graphics2D g, TgCard.Icon kind, float x, float y, float s, Color col) {
        if (kind == null) return;
        g.setColor(col);
        float w = s / 9f;
        Stroke st = new BasicStroke(w, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND);
        g.setStroke(st);
        switch (kind) {
            case CALENDAR -> {
                g.draw(new RoundRectangle2D.Float(x + w / 2, y + s * 0.16f, s - w, s * 0.80f, s * 0.22f, s * 0.22f));
                g.draw(new Line2D.Float(x + w / 2, y + s * 0.40f, x + s - w / 2, y + s * 0.40f));
                g.draw(new Line2D.Float(x + s * 0.30f, y + w / 2, x + s * 0.30f, y + s * 0.26f));
                g.draw(new Line2D.Float(x + s * 0.70f, y + w / 2, x + s * 0.70f, y + s * 0.26f));
                g.fill(new Ellipse2D.Float(x + s * 0.24f, y + s * 0.56f, s * 0.14f, s * 0.14f));
                g.fill(new Ellipse2D.Float(x + s * 0.48f, y + s * 0.56f, s * 0.14f, s * 0.14f));
            }
            case CLOCK -> {
                g.draw(new Ellipse2D.Float(x + w / 2, y + w / 2, s - w, s - w));
                g.draw(new Line2D.Float(x + s / 2, y + s / 2, x + s / 2, y + s * 0.24f));
                g.draw(new Line2D.Float(x + s / 2, y + s / 2, x + s * 0.70f, y + s * 0.62f));
            }
            case PEOPLE -> {
                g.fill(new Ellipse2D.Float(x + s * 0.30f, y + s * 0.02f, s * 0.40f, s * 0.40f));
                g.fill(new RoundRectangle2D.Float(x + s * 0.10f, y + s * 0.50f, s * 0.80f, s * 0.50f, s * 0.5f, s * 0.5f));
            }
            case PARCEL -> {
                g.draw(new RoundRectangle2D.Float(x + w / 2, y + s * 0.18f, s - w, s * 0.66f, s * 0.14f, s * 0.14f));
                Path2D v = new Path2D.Float();
                v.moveTo(x + s * 0.10f, y + s * 0.30f);
                v.lineTo(x + s * 0.5f, y + s * 0.58f);
                v.lineTo(x + s * 0.90f, y + s * 0.30f);
                g.draw(v);
            }
            case BOX -> {
                g.draw(new RoundRectangle2D.Float(x + w / 2, y + s * 0.12f, s - w, s * 0.80f, s * 0.12f, s * 0.12f));
                g.draw(new Line2D.Float(x + s * 0.5f, y + s * 0.12f, x + s * 0.5f, y + s * 0.46f));
                g.draw(new Line2D.Float(x + w / 2, y + s * 0.36f, x + s - w / 2, y + s * 0.36f));
            }
            case MONEY -> {
                g.draw(new RoundRectangle2D.Float(x + w / 2, y + s * 0.20f, s - w, s * 0.60f, s * 0.14f, s * 0.14f));
                g.draw(new Ellipse2D.Float(x + s * 0.36f, y + s * 0.36f, s * 0.28f, s * 0.28f));
            }
            case CAR -> {
                Path2D body = new Path2D.Float();
                body.moveTo(x + s * 0.06f, y + s * 0.74f);
                body.lineTo(x + s * 0.06f, y + s * 0.52f);
                body.lineTo(x + s * 0.20f, y + s * 0.48f);
                body.lineTo(x + s * 0.32f, y + s * 0.26f);
                body.lineTo(x + s * 0.70f, y + s * 0.26f);
                body.lineTo(x + s * 0.82f, y + s * 0.48f);
                body.lineTo(x + s * 0.94f, y + s * 0.52f);
                body.lineTo(x + s * 0.94f, y + s * 0.74f);
                body.closePath();
                g.draw(body);
                g.fill(new Ellipse2D.Float(x + s * 0.16f, y + s * 0.66f, s * 0.20f, s * 0.20f));
                g.fill(new Ellipse2D.Float(x + s * 0.64f, y + s * 0.66f, s * 0.20f, s * 0.20f));
            }
            case STAR -> {
                Path2D p = new Path2D.Float();
                for (int i = 0; i < 10; i++) {
                    double a = -Math.PI / 2 + i * Math.PI / 5;
                    double r = i % 2 == 0 ? s * 0.50 : s * 0.22;
                    double px = x + s / 2 + Math.cos(a) * r, py = y + s * 0.54 + Math.sin(a) * r;
                    if (i == 0) p.moveTo(px, py); else p.lineTo(px, py);
                }
                p.closePath();
                g.fill(p);
            }
            case ROUTE -> {
                g.fill(new Ellipse2D.Float(x, y + s * 0.62f, s * 0.30f, s * 0.30f));
                g.draw(new Ellipse2D.Float(x + s * 0.66f, y + s * 0.06f, s * 0.28f, s * 0.28f));
                Path2D p = new Path2D.Float();
                p.moveTo(x + s * 0.24f, y + s * 0.70f);
                p.curveTo(x + s * 0.70f, y + s * 0.70f, x + s * 0.30f, y + s * 0.30f, x + s * 0.70f, y + s * 0.24f);
                g.draw(p);
            }
            case CHECK -> {
                g.draw(new Ellipse2D.Float(x + w / 2, y + w / 2, s - w, s - w));
                Path2D p = new Path2D.Float();
                p.moveTo(x + s * 0.28f, y + s * 0.52f);
                p.lineTo(x + s * 0.44f, y + s * 0.68f);
                p.lineTo(x + s * 0.74f, y + s * 0.36f);
                g.draw(p);
            }
            case PHONE -> {
                g.draw(new RoundRectangle2D.Float(x + s * 0.24f, y + w / 2, s * 0.52f, s - w, s * 0.16f, s * 0.16f));
                g.draw(new Line2D.Float(x + s * 0.42f, y + s * 0.80f, x + s * 0.58f, y + s * 0.80f));
            }
        }
    }

    // =====================================================================
    // Matn
    // =====================================================================

    /** Ikkinchi shrift: asosiy shriftda bo'lmagan harflar uchun (Onest — lotin va kirillning hammasi bor) */
    private static volatile Font FALLBACK;

    /** Shriftda harf bo'lmasa — Onest, unda ham bo'lmasa (masalan, ismdagi boshqa yozuv) — tizim shrifti */
    static Font forText(Font f, String s) {
        if (f.canDisplayUpTo(s) == -1) return f;
        Font fb = FALLBACK;
        if (fb != null && fb.canDisplayUpTo(s) == -1) return fb.deriveFont(f.getSize2D());
        return new Font(Font.SANS_SERIF, Font.BOLD, Math.round(f.getSize2D()));
    }

    static void text(Graphics2D g, String s, Font f, float x, float y, Color c) {
        Font use = forText(f, s);
        g.setFont(use);
        g.setColor(c);
        g.drawString(s, x, y);
        remember(g, use, s, x, y);
    }

    /** Birinchi chizishda matn egallagan joylar (konfetti ularga tushmasin) */
    private static final ThreadLocal<List<Rectangle2D>> BOXES = new ThreadLocal<>();

    private static void remember(Graphics2D g, Font font, String s, float x, float y) {
        List<Rectangle2D> boxes = BOXES.get();
        if (boxes == null) return;
        FontMetrics fm = g.getFontMetrics(font);
        boxes.add(new Rectangle2D.Float(x, y - fm.getAscent(), fm.stringWidth(s), fm.getAscent() + fm.getDescent()));
    }

    static boolean near(List<Rectangle2D> boxes, float x, float y, float margin) {
        for (Rectangle2D r : boxes) {
            if (x >= r.getMinX() - margin && x <= r.getMaxX() + margin && y >= r.getMinY() - margin && y <= r.getMaxY() + margin) return true;
        }
        return false;
    }

    /** Kenglikka sig'maguncha shriftni kichraytiradi (minSize gacha), keyin «…» bilan qisqartiradi */
    static void fitText(Graphics2D g, String s, Font f, float x, float y, float maxW, float minSize, Color c) {
        Font use = forText(f, s);
        FontMetrics fm = g.getFontMetrics(use);
        while (fm.stringWidth(s) > maxW && use.getSize2D() > minSize) {
            use = use.deriveFont(use.getSize2D() - 2f);
            fm = g.getFontMetrics(use);
        }
        String t = fm.stringWidth(s) > maxW ? ellipsize(s, fm, maxW) : s;
        g.setFont(use);
        g.setColor(c);
        g.drawString(t, x, y);
        remember(g, use, t, x, y);
    }

    static String ellipsize(String s, FontMetrics fm, float maxW) {
        if (fm.stringWidth(s) <= maxW) return s;
        String dots = "…";
        int end = s.length();
        while (end > 1 && fm.stringWidth(s.substring(0, end).trim() + dots) > maxW) end--;
        return s.substring(0, end).trim() + dots;
    }

    /**
     * Sarlavhani maxLines qatorga bo'ladi: avval katta shriftda, sig'masa — kichraytirib (minSize gacha); baribir sig'masa —
     * oxirgi qator «…» bilan. @return tanlangan shrift
     */
    static Font wrap(String text, Font base, float size, float minSize, float maxW, int maxLines, List<String> out) {
        Font f = forText(base, text).deriveFont(size);
        Graphics2D probe = new BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB).createGraphics();
        try {
            hints(probe);
            while (true) {
                FontMetrics fm = probe.getFontMetrics(f);
                List<String> lines = split(text, fm, maxW);
                if (lines.size() <= maxLines) lines = balance(lines, fm, maxW);
                boolean fits = lines.size() <= maxLines && lines.stream().allMatch(l -> fm.stringWidth(l) <= maxW);
                if (fits || f.getSize2D() <= minSize) {
                    if (lines.size() > maxLines) {
                        List<String> cut = new ArrayList<>(lines.subList(0, maxLines));
                        String rest = String.join(" ", lines.subList(maxLines - 1, lines.size()));
                        cut.set(maxLines - 1, ellipsize(rest, fm, maxW));
                        lines = cut;
                    }
                    // Bitta juda uzun so'z ham rasmdan chiqib ketmasin
                    for (String line : lines) out.add(ellipsize(line, fm, maxW));
                    return f;
                }
                f = f.deriveFont(f.getSize2D() - 2f);
            }
        } finally {
            probe.dispose();
        }
    }

    /** Ikki qatorga sig'sa — qatorlar teng uzunlikka yaqin bo'lsin («Reysingizga 5 ta / e'lon mos», «… / mos» emas) */
    static List<String> balance(List<String> lines, FontMetrics fm, float maxW) {
        if (lines.size() != 2) return lines;
        String[] words = units(lines.get(0) + " " + lines.get(1)).toArray(String[]::new);
        List<String> best = lines;
        float bestMax = Math.max(fm.stringWidth(lines.get(0)), fm.stringWidth(lines.get(1)));
        for (int k = 1; k < words.length; k++) {
            String a = String.join(" ", java.util.Arrays.copyOfRange(words, 0, k));
            String b = String.join(" ", java.util.Arrays.copyOfRange(words, k, words.length));
            float m = Math.max(fm.stringWidth(a), fm.stringWidth(b));
            if (m <= maxW && m < bestMax - 0.5f) { bestMax = m; best = List.of(a, b); }
        }
        return best;
    }

    /** So'zlar; son keyingi so'z bilan birga qoladi («4 ta», «12 kishi» qatorlarga bo'linmaydi) */
    static List<String> units(String text) {
        List<String> out = new ArrayList<>();
        String[] words = text.trim().split("\\s+");
        for (int i = 0; i < words.length; i++) {
            String w = words[i];
            if (w.isEmpty()) continue;
            if (i + 1 < words.length && w.chars().allMatch(Character::isDigit)) w = w + " " + words[++i];
            out.add(w);
        }
        return out;
    }

    static List<String> split(String text, FontMetrics fm, float maxW) {
        List<String> lines = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        for (String word : units(text)) {
            String next = cur.isEmpty() ? word : cur + " " + word;
            if (fm.stringWidth(next) <= maxW || cur.isEmpty()) {
                cur.setLength(0);
                cur.append(next);
            } else {
                lines.add(cur.toString());
                cur.setLength(0);
                cur.append(word);
            }
        }
        if (!cur.isEmpty()) lines.add(cur.toString());
        return lines;
    }

    static Color alpha(Color c, int a) {
        return new Color(c.getRed(), c.getGreen(), c.getBlue(), Math.max(0, Math.min(255, a)));
    }
}
