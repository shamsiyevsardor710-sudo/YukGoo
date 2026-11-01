// Telegram Mini App rejimi: YukGo bot ichida ochilganda — avtomatik kirish, "Orqaga" tugmasi, ranglar, havolalar.
// Oddiy brauzerda bu modul hech narsa qilmaydi va telegram.org skripti umuman yuklanmaydi.
import { auth, post } from './api.js';
import { parseStartParam, rememberAttribution } from './attribution.js';
import { currentTheme, onThemeChange } from './theme.js';

const SCRIPT = 'https://telegram.org/js/telegram-web-app.js';
const INIT_KEY = 'yukgo_tg_init';
/** Telegram ichida "Chiqish" bosilgan — parol bilan kirilmaguncha avtomatik kirmaymiz (server javobidan qat'i nazar) */
const OUT_KEY = 'yukgo_tg_out';
const OUT_HINT = "Siz Telegram ichida hisobdan chiqqansiz. Qayta kirish uchun parolingizni kiriting";
function lsGet(k) { try { return localStorage.getItem(k); } catch { return null; } }
function lsSet(k, v) { try { if (v == null) localStorage.removeItem(k); else localStorage.setItem(k, v); } catch { /* */ } }

/**
 * active   — sayt Telegram ichida ochilgan;
 * app      — Telegram.WebApp (skript yuklanmasa null — unda tugma/ranglarsiz, lekin kirish baribir ishlaydi);
 * initData — Telegram imzolagan ma'lumot (server shu bilan foydalanuvchini taniydi);
 * notLinked — bu Telegram hali YukGo hisobiga ulanmagan (bir marta parol bilan kirish kerak).
 */
export const tg = { app: null, active: false, initData: '', notLinked: false, loginHint: '' };

function ssGet(k) { try { return sessionStorage.getItem(k); } catch { return null; } }
function ssSet(k, v) { try { sessionStorage.setItem(k, v); } catch { /* */ } }

/**
 * Telegram ishga tushirish ma'lumotlarini manzilning "#" qismida beradi: "#tgWebAppData=...&tgWebAppVersion=...".
 * Sayt manzilida o'zi "#/yo'l" bo'lsa — "#/yo'l?tgWebAppData=...".
 * @returns {{data: string, path: string|null, startParam: string|null}|null}
 */
function readLaunchHash() {
  const h = location.hash.replace(/^#/, '');
  if (!h.includes('tgWebAppData=')) return null;
  const withPath = h.startsWith('/');
  const params = new URLSearchParams(withPath ? h.slice(h.indexOf('?') + 1) : h);
  const data = params.get('tgWebAppData') || '';
  return { data, path: withPath ? h.split('?')[0] : null, startParam: startParamOf(params, data) };
}

/**
 * "t.me/BOT?startapp=o123_ch3" havolasi bilan ochilganda Telegram parametrni manzilning so'rov qismida
 * (?tgWebAppStartParam=...) va initData ichida (start_param) beradi; ba'zi ilovalar # qismiga ham qo'yadi.
 */
function startParamOf(hashParams, initData) {
  let fromQuery = null;
  try { fromQuery = new URLSearchParams(location.search).get('tgWebAppStartParam'); } catch { /* */ }
  let fromInit = null;
  try { fromInit = new URLSearchParams(initData || '').get('start_param'); } catch { /* */ }
  return fromQuery || hashParams.get('tgWebAppStartParam') || fromInit || null;
}

/** Sahifa Telegram ichida qayta yuklansa — avval saqlangan nusxa (bizniki yoki Telegram skriptiniki) */
function storedInitData() {
  const own = ssGet(INIT_KEY);
  if (own) return own;
  try { return JSON.parse(ssGet('__telegram__initParams') || '{}').tgWebAppData || ''; } catch { return ''; }
}

function loadScript(src, timeoutMs) {
  return new Promise((resolve, reject) => {
    const s = document.createElement('script');
    const timer = setTimeout(() => reject(new Error('timeout')), timeoutMs);
    s.src = src;
    s.onload = () => { clearTimeout(timer); resolve(); };
    s.onerror = () => { clearTimeout(timer); reject(new Error('load')); };
    document.head.appendChild(s);
  });
}

// Bot tugmalari sahifani "?r=/orders/5" ko'rinishida beradi — faqat ilova ichidagi yo'l bo'lsin
const safeRoute = (p) => typeof p === 'string' && /^\/[A-Za-z0-9_\-./?=&%]*$/.test(p) && !p.startsWith('//');

/**
 * Telegram ichida sarlavha va fon rangi ilova rejimiga moslanadi. Rejim — YukGo'ning o'zi (birinchi kirishda kechki,
 * keyin foydalanuvchi tanlagani); Telegram mavzusi uni o'zgartirmaydi.
 */
function syncTheme(app) {
  const dark = currentTheme() === 'dark';
  try {
    app.setHeaderColor?.(dark ? '#0C1113' : '#1F2A30');
    app.setBackgroundColor?.(dark ? '#12181B' : '#EEF1F0');
  } catch { /* eski Telegram versiyasi */ }
}

/** Ilova ishga tushishidan oldin (boot) chaqiriladi. Oddiy brauzerda darhol qaytadi. */
export async function initTelegram() {
  const launch = readLaunchHash();
  let initData = launch?.data || '';
  if (initData) ssSet(INIT_KEY, initData);
  else initData = storedInitData();
  if (!initData) return;
  tg.active = true;
  tg.initData = initData;
  document.documentElement.classList.add('in-telegram');

  // Telegram skripti ma'lumotlarni manzildan o'qishi kerak — shuning uchun manzil skriptdan KEYIN tozalanadi.
  // Skript yuklanmasa ham (sekin internet) 6 soniyadan keyin davom etamiz: kirish initData bilan baribir ishlaydi.
  try { await loadScript(SCRIPT, 6000); } catch { /* skriptsiz davom etamiz */ }

  const fromButton = new URLSearchParams(location.search).get('r');
  // Kanal postidagi tugma (t.me/BOT?startapp=o123_ch3): qaysi buyurtma va qaysi kanaldan kelgani
  const start = parseStartParam(launch?.startParam);
  if (start) rememberAttribution(start.src, start.ref);
  if (launch || fromButton != null) {
    const h = location.hash;
    const fromHash = launch ? launch.path : (h.startsWith('#/') ? h.slice(1) : null);
    const route = safeRoute(fromButton) ? fromButton : (safeRoute(fromHash) ? fromHash : (start ? start.route : '/'));
    history.replaceState({ tgStart: true }, '', location.pathname + '#' + route);
  }

  const app = window.Telegram?.WebApp;
  if (app) {
    tg.app = app;
    if (app.initData) tg.initData = app.initData;
    try {
      app.ready();
      app.expand();
      app.disableVerticalSwipes?.();   // xaritani pastga surganda ilova yopilib ketmasin
    } catch { /* */ }
    syncTheme(app);
    onThemeChange(() => syncTheme(app));   // foydalanuvchi kunduzgi/kechki rejimni almashtirganda
    // Telegram'ning "Orqaga" tugmasi: birinchi ochilgan sahifada bo'lsak — bosh sahifaga, aks holda bir qadam orqaga
    try {
      app.BackButton?.onClick(() => {
        if (history.state && history.state.tgStart) location.hash = '#/';
        else history.back();
      });
    } catch { /* */ }
  }
  interceptLinks();
}

/**
 * Telegram ichida tashqi havolalar (maxfiylik siyosati, Yandex, t.me…) Telegram usulida ochiladi:
 * WebView ichida boshqa saytga o'tib ketsa, ilovaga qaytish qiyin.
 */
function interceptLinks() {
  document.addEventListener('click', (e) => {
    const app = tg.app;
    if (!app || e.defaultPrevented || e.button > 0 || e.metaKey || e.ctrlKey) return;
    const a = e.target.closest && e.target.closest('a[href]');
    if (!a) return;
    const href = a.getAttribute('href');
    if (!href || href.startsWith('#')) return;
    let url;
    try { url = new URL(href, location.href); } catch { return; }
    if (url.protocol !== 'https:' && url.protocol !== 'http:') return;   // tel:, mailto: — tizim o'zi ochadi
    const sameApp = url.origin === location.origin && !/\.html$/.test(url.pathname);
    if (sameApp && a.target !== '_blank') return;
    e.preventDefault();
    try {
      if (url.hostname === 't.me' && app.openTelegramLink) app.openTelegramLink(url.href);
      else app.openLink(url.href);
    } catch {
      window.open(url.href, '_blank', 'noopener');
    }
  }, true);
}

/**
 * Telegram orqali avtomatik kirish (hisob shu Telegramga ulangan bo'lsa).
 * @returns {Promise<boolean>} kirdimi
 */
export async function telegramAutoLogin() {
  if (!tg.active || !tg.initData || auth.token) return false;
  if (lsGet(OUT_KEY)) { tg.loginHint = OUT_HINT; return false; }
  try {
    const res = await post('/auth/telegram', { initData: tg.initData });
    auth.token = res.token;
    auth.user = res.user;
    tg.notLinked = false;
    tg.loginHint = '';
    return true;
  } catch (e) {
    tg.notLinked = e.status === 404;
    // Server sababini aytadi: hali ulanmagan / admin har safar parol bilan kiradi / Telegramda chiqib ketgan
    tg.loginHint = e.status === 404 || e.status === 403 ? e.message : '';
    return false;
  }
}

/** Kirish, ro'yxatdan o'tish va parolni tiklash so'rovlariga qo'shiladi: hisob shu Telegramga ulanadi. */
export function telegramInitData() {
  return tg.active && tg.initData ? tg.initData : undefined;
}

/** Telegram ichida "Chiqish": keyingi ochilishda avtomatik kirmasin (buyurtma xabarlari esa kelaveradi). */
export async function telegramLogout() {
  if (!tg.active) return;
  lsSet(OUT_KEY, '1');   // avval belgi: so'rov 401 qaytarsa ham "jim qayta kirish" ishlamaydi
  tg.loginHint = OUT_HINT;
  if (!auth.token) return;
  try { await post('/users/me/telegram/logout'); } catch { /* tarmoq bo'lmasa ham chiqib ketamiz */ }
}

/** Parol (yoki Telegram kodi) bilan kirildi — Telegram ichida yana avtomatik kirish mumkin. */
export function telegramLoggedIn() {
  lsSet(OUT_KEY, null);
  tg.loginHint = '';
}

/** Har sahifa ochilganda: bosh sahifadan boshqa joyda "Orqaga" tugmasi ko'rinadi. */
export function telegramRoute(path) {
  const bb = tg.app && tg.app.BackButton;
  if (!bb) return;
  try {
    if (path === '/') bb.hide();
    else bb.show();
  } catch { /* */ }
}
