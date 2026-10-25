// Sayt qayerda ochilgan: Google Play'dagi Android ilova (Capacitor) yoki Telegram Mini App.
// Ikkala do'kon ham raqamli xizmat uchun pulni faqat o'z to'lov tizimi orqali olishni talab qiladi
// (Google Play Billing, Telegram Stars). Shuning uchun bu joylarda pullik «Shoshilinch» taklif qilinmaydi.
// Komissiya — yuk tashish (jismoniy xizmat) uchun, unga bu talab qo'yilmaydi: balansni to'ldirish hamma joyda ishlaydi.
import { tg } from './telegram.js';

/** Android ilova ichida (ilova sayt so'roviga "YukGoApp" belgisini qo'shadi) */
export function inAndroidApp() {
  try {
    const cap = window.Capacitor;
    if (cap && cap.isNativePlatform && cap.isNativePlatform()) return true;
  } catch { /* */ }
  return /YukGoApp/i.test(navigator.userAgent || '');
}

/** Shu yerda raqamli xizmatni (pullik «Shoshilinch») sotib bo'lmaydi */
export function digitalSalesBlocked() {
  return inAndroidApp() || tg.active;
}
