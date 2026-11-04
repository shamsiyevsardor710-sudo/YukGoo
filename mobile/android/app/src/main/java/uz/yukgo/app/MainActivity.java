package uz.yukgo.app;

import android.os.Bundle;
import com.getcapacitor.BridgeActivity;

/**
 * YukGo ilovasi: jonli saytni ochadi (capacitor.config.json → server.url).
 * Bildirishnoma ruxsatini (Android 13+) sayt so'raydi — foydalanuvchi kirgach, bir marta (js/push.js): yangi xabar va
 * takliflar (push) hamda haydovchi navigatsiyasidagi "joylashuv yuboruvchiga ko'rsatilmoqda" bildirishnomasi uchun.
 * Bu yerda alohida so'ralmaydi: ikki so'rov bir vaqtda chiqsa, Android ikkinchisini bekor qiladi.
 */
public class MainActivity extends BridgeActivity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        registerPlugin(YukGoPushPlugin.class);   // ilovaning o'z plagini: push sozlanganmi (super.onCreate'dan oldin)
        super.onCreate(savedInstanceState);
    }
}
