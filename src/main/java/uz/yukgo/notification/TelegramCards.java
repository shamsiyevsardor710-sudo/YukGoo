package uz.yukgo.notification;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import uz.yukgo.telegram.TelegramClient;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Rasmli Telegram xabarlari: karta bir marta chiziladi va Telegramga bir marta yuklanadi — bir xil kartani olgan
 * keyingi foydalanuvchilarga (masalan, bitta e'lon 100 ta haydovchiga) Telegramdagi rasm ID si bilan yuboriladi.
 * Rasm chizilmasa yoki yuborilmasa — o'sha matn oddiy xabar bo'lib ketadi. Railway'da TELEGRAM_CARDS=false — rasmsiz.
 */
@Slf4j
@Component
public class TelegramCards {
    /** Shuncha kartaning Telegram ID si eslab qolinadi (eng eskisi unutiladi) */
    static final int CACHE = 2000;
    /** Telegram rasm izohi chegarasi (1024 belgi); HTML teglari bilan hisoblaymiz — ehtiyot uchun */
    static final int CAPTION_LIMIT = 1024;

    private final TelegramClient telegram;
    private final CardRenderer renderer;
    private final boolean on;

    private final Map<String, String> fileIds = Collections.synchronizedMap(new LinkedHashMap<>(256, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, String> e) {
            return size() > CACHE;
        }
    });
    /** Bir xil karta bir vaqtda ikki marta yuklanmasin (lekin har xil kartalar bir-birini kutmasin) */
    private final Object[] locks = new Object[32];

    @Autowired
    public TelegramCards(TelegramClient telegram, CardRenderer renderer, Environment env) {
        this(telegram, renderer, !"false".equalsIgnoreCase(String.valueOf(env.getProperty("TELEGRAM_CARDS", "true")).trim()));
    }

    TelegramCards(TelegramClient telegram, CardRenderer renderer, boolean on) {
        this.telegram = telegram;
        this.renderer = renderer;
        this.on = on;
        for (int i = 0; i < locks.length; i++) locks[i] = new Object();
    }

    /** Rasmli xabar yuborish mumkinmi (sozlamada yoqilgan, bot ulangan, shriftlar yuklangan) */
    public boolean enabled() {
        return on && telegram.enabled() && renderer.available();
    }

    /** Fon rejimida: rasm + izoh, tugmalar variantlari bilan; bo'lmasa — matnli xabar */
    @Async
    public void send(long chatId, TgCard card, String captionHtml, List<Object> markups) {
        sendNow(chatId, card, captionHtml, markups);
    }

    /** @return xabar (rasmli yoki matnli) yetib bordimi */
    public boolean sendNow(long chatId, TgCard card, String captionHtml, List<Object> markups) {
        if (card == null || !enabled() || captionHtml == null || captionHtml.length() > CAPTION_LIMIT) {
            return telegram.sendNowWithFallbacks(chatId, captionHtml, markups);
        }
        String key = card.key();
        try {
            String fid = fileIds.get(key);
            if (fid != null) {
                TelegramClient.PhotoResult r = telegram.sendPhotoNow(chatId, null, fid, captionHtml, markups);
                if (r.sent()) return true;
                if (r.gone()) return false;
                fileIds.remove(key, fid);   // Telegram eski ID ni qabul qilmadi — qayta yuklaymiz
            }
            synchronized (locks[Math.floorMod(key.hashCode(), locks.length)]) {
                fid = fileIds.get(key);
                if (fid == null) {
                    byte[] png = renderer.render(card);
                    TelegramClient.PhotoResult r = telegram.sendPhotoNow(chatId, png, null, captionHtml, markups);
                    if (r.sent()) {
                        if (r.fileId() != null) fileIds.put(key, r.fileId());
                        return true;
                    }
                    if (r.gone()) return false;
                }
            }
            if (fid != null) {   // shu orada boshqa oqim yukladi
                TelegramClient.PhotoResult r = telegram.sendPhotoNow(chatId, null, fid, captionHtml, markups);
                if (r.sent()) return true;
                if (r.gone()) return false;
            }
        } catch (Exception e) {
            log.warn("Rasmli xabar yuborilmadi (chat={}), matn yuboriladi: {}", chatId, TelegramClient.safe(e));
        }
        return telegram.sendNowWithFallbacks(chatId, captionHtml, markups);
    }

    /** Sinov uchun: eslab qolingan kartalar soni */
    int cached() {
        return fileIds.size();
    }
}
