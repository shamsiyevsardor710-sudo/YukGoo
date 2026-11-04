// Android ilovada push-bildirishnomalar (Firebase Cloud Messaging). Oddiy brauzerda va Telegram Mini App'da hech narsa
// qilmaydi. Ilova Firebase'siz (google-services.json'siz) yig'ilgan bo'lsa push yoqilmaydi: bunday ilovada ro'yxatdan
// o'tish urinishi ilovani yopib qo'yadi, shuning uchun avval ilovaning o'zidan so'raladi (YukGoPush.isConfigured).
// Bildirishnoma ruxsati (Android 13+) baribir so'raladi — haydovchi navigatsiyasidagi «joylashuv ko'rsatilmoqda» uchun ham kerak.
import { api, auth } from './api.js';
import { toast } from './ui.js';

const KEY = 'yukgo_push_token';
let setup = null;          // tinglovchilar va kanal — bir marta (Promise<boolean>: Firebase bormi)
let registeredFor = null;  // qaysi foydalanuvchi uchun ro'yxatdan o'tildi (boshqa hisob bilan kirilsa — qayta)
let asked = false;         // ruxsat shu ochilishda so'raldimi (rad etilsa — har safar qayta so'ramaymiz)
let busy = false;
let handlers = {};

function plugins() {
  try {
    const cap = window.Capacitor;
    if (!cap || typeof cap.isNativePlatform !== 'function' || !cap.isNativePlatform() || !cap.Plugins) return null;
    const P = cap.Plugins.PushNotifications;
    const Y = cap.Plugins.YukGoPush;   // eski ilova versiyasida yo'q — push ham yo'q
    if (!P || !Y || typeof P.register !== 'function' || typeof Y.isConfigured !== 'function') return null;
    return { P, Y, App: cap.Plugins.App };
  } catch { return null; }
}

function saved(v) {
  try {
    if (v === undefined) return localStorage.getItem(KEY);
    if (v === null) localStorage.removeItem(KEY); else localStorage.setItem(KEY, v);
  } catch { /* xotira yopiq */ }
  return null;
}

async function sendToken(token) {
  if (!auth.token || !token) return;
  try {
    await api('/push/register', { method: 'POST', body: { token, platform: 'android' }, silent: true });
    saved(token);
  } catch {
    registeredFor = null;   // keyingi sahifa yoki ilova oldinga chiqqanda yana urinadi
  }
}

async function prepare({ P, Y, App }) {
  // Ruxsatni tizim sozlamalarida berib qaytsa — ilova oldinga chiqqanda yana tekshiriladi
  try { if (App && typeof App.addListener === 'function') await App.addListener('resume', () => { void initPush(handlers); }); } catch { /* */ }
  const { configured } = await Y.isConfigured();
  if (!configured) return false;
  await P.addListener('registration', (t) => { void sendToken(t && t.value); });
  await P.addListener('registrationError', () => { registeredFor = null; });
  // Ilova ochiq turganda tizim bildirishnomasi chiqmaydi — belgini yangilaymiz va qisqa xabar ko'rsatamiz
  await P.addListener('pushNotificationReceived', (n) => {
    if (handlers.onReceive) handlers.onReceive();
    const link = (n && n.data && n.data.link) || '';
    const here = location.hash.split('?')[0];
    if (n && n.title && !(link && here === link.split('?')[0])) toast(n.body ? `${n.title}: ${n.body}` : n.title);
  });
  // Bildirishnoma bosilganda — tegishli sahifa (masalan, yozishma)
  await P.addListener('pushNotificationActionPerformed', (a) => {
    const link = a && a.notification && a.notification.data && a.notification.data.link;
    if (typeof link === 'string' && link.startsWith('#/') && handlers.onOpen) handlers.onOpen(link);
  });
  try {
    await P.createChannel({ id: 'yukgo', name: 'YukGo', description: "Xabarlar, takliflar va yangi e'lonlar",
      importance: 4, visibility: 1, vibration: true });
  } catch { /* eski Android — kanal kerak emas */ }
  return true;
}

/**
 * Kirgan foydalanuvchi uchun: ruxsat (bir marta so'raladi) va qurilmani serverda shu hisobga ro'yxatdan o'tkazish.
 * Har sahifada chaqirilsa ham bo'ladi: shu hisob uchun bajarilgan bo'lsa — hech narsa qilmaydi.
 * @param {{onOpen?: (link: string) => void, onReceive?: () => void}} h
 */
export async function initPush(h = {}) {
  if (h && (h.onOpen || h.onReceive)) handlers = h;
  const uid = auth.user && auth.user.id;
  if (!uid || !auth.token || registeredFor === uid || busy) return;
  const p = plugins();
  if (!p) return;
  busy = true;
  try {
    setup ??= prepare(p);
    const configured = await setup;
    let perm = await p.P.checkPermissions();
    if ((perm.receive === 'prompt' || perm.receive === 'prompt-with-rationale') && !asked) {
      asked = true;
      perm = await p.P.requestPermissions();
    }
    if (perm.receive !== 'granted' || !configured) return;
    await p.P.register();   // token 'registration' tinglovchisiga keladi
    registeredFor = uid;
  } catch { /* push ishlamasa ham ilova ishlayveradi */ } finally {
    busy = false;
  }
}

/**
 * Chiqishda yoki sessiya tugaganda: bu telefonga endi shu hisobning xabarlari kelmasin.
 * server=false — kirish tokeni allaqachon yaroqsiz (keyingi kirishda token yangi hisobga o'tadi).
 */
export async function stopPush({ server = true } = {}) {
  const token = saved();
  registeredFor = null;
  if (!server) return;
  saved(null);
  if (token && auth.token) {
    try { await api('/push/unregister', { method: 'POST', body: { token }, silent: true }); } catch { /* */ }
  }
}
