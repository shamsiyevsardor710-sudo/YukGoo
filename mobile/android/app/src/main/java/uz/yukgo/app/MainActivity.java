package uz.yukgo.app;

import android.Manifest;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import com.getcapacitor.BridgeActivity;

/**
 * YukGo ilovasi: jonli saytni ochadi (capacitor.config.json → server.url).
 * Android 13+ da bildirishnoma ruxsati so'raladi — haydovchi navigatsiyasida
 * "joylashuv yuboruvchiga ko'rsatilmoqda" bildirishnomasi ko'rinib turishi uchun.
 */
public class MainActivity extends BridgeActivity {
    private static final int NOTIFICATIONS_REQUEST = 1001;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (Build.VERSION.SDK_INT >= 33
                && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, new String[] { Manifest.permission.POST_NOTIFICATIONS }, NOTIFICATIONS_REQUEST);
        }
    }
}
