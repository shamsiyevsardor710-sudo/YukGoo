// Foydalanuvchi qaysi havola bilan kelgani: manba (?src=KOD — kanal, hamkor, varaqa) va taklif (?ref=ID — do'sti).
// Birinchi tashrifda shu brauzerda eslab qolinadi (30 kun) va ro'yxatdan o'tishda serverga yuboriladi.
// Shaxsiy ma'lumot emas — faqat havola kodi.
const SRC_KEY = 'yukgo_src';
const REF_KEY = 'yukgo_ref';
const TTL_MS = 30 * 24 * 3600 * 1000;
const SRC_RE = /^[a-z0-9_-]{1,40}$/;
const REF_RE = /^\d{1,18}$/;

function read(key) {
  try {
    const v = JSON.parse(localStorage.getItem(key) || 'null');
    if (v && typeof v.at === 'number' && Date.now() - v.at < TTL_MS) return v.v;
  } catch { /* */ }
  return null;
}

function write(key, value) {
  try { localStorage.setItem(key, JSON.stringify({ v: value, at: Date.now() })); } catch { /* */ }
}

/** Birinchi kelgan havola ustun: avval eslab qolingan bo'lsa — almashtirilmaydi */
export function rememberAttribution(src, ref) {
  const s = typeof src === 'string' ? src.trim().toLowerCase() : '';
  const r = typeof ref === 'string' ? ref.trim().replace(/^(ref_|r)/, '') : '';
  if (SRC_RE.test(s) && !read(SRC_KEY)) write(SRC_KEY, s);
  if (REF_RE.test(r) && !read(REF_KEY)) write(REF_KEY, r);
}

/** Manzildagi ?src= va ?ref= ni eslab qoladi va manzildan olib tashlaydi (qolgan parametrlar va # qismi saqlanadi) */
export function captureAttribution() {
  let params;
  try { params = new URLSearchParams(location.search); } catch { return; }
  if (!params.has('src') && !params.has('ref')) return;
  rememberAttribution(params.get('src'), params.get('ref'));
  params.delete('src');
  params.delete('ref');
  const qs = params.toString();
  try { history.replaceState(history.state, '', location.pathname + (qs ? '?' + qs : '') + location.hash); } catch { /* */ }
}

/** Ro'yxatdan o'tish so'roviga qo'shiladi */
export function attribution() {
  return { source: read(SRC_KEY) || undefined, ref: read(REF_KEY) || undefined };
}

export function clearAttribution() {
  try {
    localStorage.removeItem(SRC_KEY);
    localStorage.removeItem(REF_KEY);
  } catch { /* */ }
}

/**
 * Telegram havolasidagi start parametri (t.me/BOT?startapp=...): qaysi sahifani ochish va qaysi manbadan kelgani.
 *  o123_ch3 — buyurtma #123 (kanal #3 postidan), new_ch3 — yangi e'lon, src_KOD — manba, ref_ID — taklif
 * @returns {{route: string, src?: string, ref?: string}|null}
 */
export function parseStartParam(p) {
  if (typeof p !== 'string' || !p) return null;
  let m = p.match(/^o(\d{1,18})(?:_([a-z0-9_-]{1,40}))?$/);
  if (m) return { route: '/orders/' + m[1], src: m[2] };
  m = p.match(/^new(?:_([a-z0-9_-]{1,40}))?$/);
  if (m) return { route: '/new', src: m[1] };
  m = p.match(/^src_([a-z0-9_-]{1,40})$/);
  if (m) return { route: '/', src: m[1] };
  m = p.match(/^ref_(\d{1,18})$/);
  if (m) return { route: '/register', ref: m[1] };
  return null;
}
