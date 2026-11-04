package uz.yukgo.app;

import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

/**
 * Ilova Firebase bilan yig'ilganmi (yig'ishda android/app/google-services.json bor edimi). Firebase'siz
 * PushNotifications.register() ilovani yopib qo'yadi, shuning uchun sayt avval shuni so'raydi (js/push.js).
 * google_app_id — google-services.json'dan yig'ishda yaratiladigan qiymat: Firebase ham aynan shu bo'yicha ishga tushadi.
 */
@CapacitorPlugin(name = "YukGoPush")
public class YukGoPushPlugin extends Plugin {
    @PluginMethod
    public void isConfigured(PluginCall call) {
        int id = getContext().getResources().getIdentifier("google_app_id", "string", getContext().getPackageName());
        JSObject result = new JSObject();
        result.put("configured", id != 0);
        call.resolve(result);
    }
}
