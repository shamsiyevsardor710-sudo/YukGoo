// Umumiy UI yordamchilari: HTML qochirish, formatlash, modal, toast, belgilar
import { regions, districts } from './api.js';

export const esc = (v) => String(v ?? '').replace(/[&<>"']/g, c => ({
  '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));

export const $ = (sel, root = document) => root.querySelector(sel);
export const $$ = (sel, root = document) => [...root.querySelectorAll(sel)];

export function money(v) {
  if (v === null || v === undefined) return 'Narx kelishiladi';
  return Math.round(v).toLocaleString('ru-RU').replace(/ /g, ' ') + " so'm";
}
export function num(v, digits = 0) {
  if (v === null || v === undefined) return '';
  return Number(v).toLocaleString('ru-RU', { maximumFractionDigits: digits }).replace(/ /g, ' ');
}
export function km(v) { return v == null ? '' : num(v, v < 10 ? 1 : 0) + ' km'; }
export function duration(min) {
  if (min == null) return '';
  const h = Math.floor(min / 60), m = Math.round(min % 60);
  return h ? `${h} soat ${m ? m + ' daq' : ''}`.trim() : `${m} daq`;
}
const MONTHS = ['yanvar', 'fevral', 'mart', 'aprel', 'may', 'iyun', 'iyul', 'avgust', 'sentabr', 'oktabr', 'noyabr', 'dekabr'];
export function date(v) {
  if (!v) return '';
  const d = new Date(v);
  return `${d.getDate()}-${MONTHS[d.getMonth()]}${d.getFullYear() !== new Date().getFullYear() ? ' ' + d.getFullYear() : ''}`;
}
export function dateTime(v) {
  if (!v) return '';
  const d = new Date(v);
  return `${date(v)}, ${String(d.getHours()).padStart(2, '0')}:${String(d.getMinutes()).padStart(2, '0')}`;
}
export function ago(v) {
  if (!v) return '';
  const s = (Date.now() - new Date(v).getTime()) / 1000;
  if (s < 60) return 'hozirgina';
  if (s < 3600) return Math.floor(s / 60) + ' daqiqa oldin';
  if (s < 86400) return Math.floor(s / 3600) + ' soat oldin';
  if (s < 86400 * 7) return Math.floor(s / 86400) + ' kun oldin';
  return date(v);
}
export function phoneFmt(p) {
  if (!p) return '';
  const d = p.replace(/\D/g, '');
  if (d.length !== 12) return p;
  return `+${d.slice(0, 3)} ${d.slice(3, 5)} ${d.slice(5, 8)} ${d.slice(8, 10)} ${d.slice(10)}`;
}

// ---- Telefon maydonlari (type="tel"): «+998» o'zi yoziladi, qolgani «90 123 45 67» ko'rinishida ----
export const PHONE_PREFIX = '+998 ';

/**
 * Maydondagi matndan mahalliy 9 xonali qism (998 kodisiz) va boshidan olib tashlangan raqamlar soni (lead).
 * "+998 90 123 45 67", "998901234567", "901234567", "+998 " ustiga yopishtirilgan to'liq raqam — hammasi to'g'ri.
 * Ucell'ning «99 8..» raqami ham: 9 xonali raqam (ortiqcha 998siz) o'zgarmaydi.
 */
export function phoneParts(v) {
  const s = String(v ?? '');
  let d = s.replace(/\D/g, '');
  let lead = 0;
  if (/^\s*\+/.test(s) || d.length >= 12) {
    if (d.startsWith('998')) { d = d.slice(3); lead = 3; }
    if (d.length > 9 && d.startsWith('998')) { d = d.slice(3); lead += 3; }
  }
  return { local: d.slice(0, 9), lead };
}

/** Mahalliy raqamlardan maydon qiymati: "+998 90 123 45 67" (to'liq bo'lmasa — shu paytgacha yozilgani) */
function phoneFromLocal(d) {
  return PHONE_PREFIX + [d.slice(0, 2), d.slice(2, 5), d.slice(5, 7), d.slice(7, 9)].filter(Boolean).join(' ');
}

export function phoneInputValue(v) {
  return phoneFromLocal(phoneParts(v).local);
}

const phonePrev = new WeakMap();
/**
 * Bo'sh maydondan boshlab ketma-ket (oxiriga, bittadan) yozilgan raqamlar; ketma-ketlik buzilsa (yopishtirish, o'rtasiga
 * yozish, oldindan to'ldirilgan raqam) — null. «+998» dan keyin odat bo'yicha yana «998 90 …» deb yozilayotganini
 * (ortiqcha 998 olib tashlanadi) tayyor «99 8…» raqamga tasodifan qo'shilgan raqamdan ajratish uchun.
 */
const phoneTyped = new WeakMap();

const onlyDigits = (s) => s.replace(/\D/g, '');

/**
 * Har bir o'zgarishda qayta formatlash; kursor o'sha raqamning yonida qoladi. «+998 » o'chirilmaydi: uning ichida
 * o'chirilsa — joyiga qaytadi (faqat mahalliy qismdan o'chirilgan raqamlar ketadi), ichiga yozilgan raqam esa mahalliy
 * raqam boshiga o'tadi. Raqam to'liq (9 xona) bo'lsa — ortiqcha raqam qo'shilmaydi.
 */
function formatPhoneField(input, inputType) {
  const raw = input.value;
  const focused = document.activeElement === input;
  const caret = focused && input.selectionStart != null ? input.selectionStart : raw.length;
  const prev = phonePrev.get(input);
  const prevLocal = prev != null ? phoneParts(prev).local : '';
  const stored = phoneTyped.get(input);
  const typedBefore = stored !== undefined ? stored : (prevLocal === '' ? '' : null);
  const P = PHONE_PREFIX.length;
  const inserting = typeof inputType === 'string' && inputType.startsWith('insert');
  let local, before, typing = false;
  if (raw.startsWith(PHONE_PREFIX)) {
    // Prefiks joyida: qolgani — mahalliy raqam
    const d = onlyDigits(raw.slice(P));
    typing = inputType === 'insertText' && caret >= raw.length && d.length === prevLocal.length + 1;
    // Ortiqcha 998: ustiga to'liq raqam yopishtirilgan («998 90 123 45 67») yoki «998…» deb boshidan yozilmoqda
    const dup = d.length > 9 && d.startsWith('998')
      && (d.length >= 12 || (typing && typedBefore != null && typedBefore.startsWith('998'))) ? 3 : 0;
    if (!dup && inserting && prevLocal.length === 9 && d.length > 9) {
      // Raqam to'liq — ortiqcha raqam (oxiriga ham, o'rtasiga ham) qo'shilmaydi
      local = prevLocal;
      before = Math.max(0, Math.min(9, onlyDigits(raw.slice(P, Math.max(P, caret))).length - (d.length - 9)));
      typing = false;
    } else {
      local = d.slice(dup, dup + 9);
      before = Math.max(0, Math.min(local.length, onlyDigits(raw.slice(P, Math.max(P, caret))).length - dup));
      // Probel o'chirildi (raqamlar o'zgarmadi) — undan oldingi raqam o'chsin, aks holda «Backspace» ishlamagandek tuyuladi
      if (inputType === 'deleteContentBackward' && prev != null && local === prevLocal && before > 0) {
        local = local.slice(0, before - 1) + local.slice(before);
        before--;
      }
    }
  } else if (inputType && inputType.startsWith('delete') && prev != null && prev.startsWith(PHONE_PREFIX)
    && raw.length < prev.length) {
    // Prefiks ichida (yoki uni ham qamrab) o'chirildi: prefiks qoladi, mahalliy qismdan faqat o'chirilgani ketadi
    const from = caret, to = caret + (prev.length - raw.length);
    let kept = '';
    for (let i = P; i < prev.length; i++) if (i < from || i >= to) kept += prev[i];
    local = onlyDigits(kept).slice(0, 9);
    before = Math.min(local.length, onlyDigits(prev.slice(P, Math.max(P, from))).length);
  } else if (inserting && prev != null && prev.startsWith(PHONE_PREFIX) && raw.length > prev.length) {
    // «+998» ichiga yozildi yoki yopishtirildi: prefiks joyida qoladi, kiritilgan raqam — mahalliy raqam boshiga
    const k = raw.length - prev.length;
    const added = phoneParts(raw.slice(Math.max(0, caret - k), caret)).local;
    if (added.length >= 9) { local = added; before = 9; }                          // to'liq raqam yopishtirildi
    else if (prevLocal.length === 9) { local = prevLocal; before = 0; }            // raqam to'liq — qo'shilmaydi
    else { local = (added + prevLocal).slice(0, 9); before = Math.min(local.length, added.length); }
  } else {
    // Yangidan yozildi yoki yopishtirildi: «+998901234567», «998901234567», «90 123 45 67»
    const parts = phoneParts(raw);
    local = parts.local;
    before = Math.max(0, Math.min(local.length, onlyDigits(raw.slice(0, caret)).length - parts.lead));
  }
  const next = phoneFromLocal(local);
  if (next !== raw) input.value = next;
  phonePrev.set(input, next);
  // Ketma-ket yozilganlar: bittadan oxiriga — qo'shiladi; oxiridan o'chirildi — qisqaradi; boshqa o'zgarish — uziladi
  let typed = null;
  if (local === '') typed = '';
  else if (typing && typedBefore != null) typed = typedBefore + raw.slice(-1);
  else if (typedBefore != null && inputType && inputType.startsWith('delete') && typedBefore.startsWith(local)) typed = local;
  phoneTyped.set(input, typed);
  if (!focused) return;
  let pos = P, seen = 0;
  while (seen < before && pos < next.length) { if (/\d/.test(next[pos])) seen++; pos++; }
  try { input.setSelectionRange(pos, pos); } catch { /* */ }
}

let phoneInputsReady = false;

/**
 * Barcha telefon maydonlari uchun bir marta (ilova ishga tushganda): bosilganda «+998 » yoziladi va kursor oxiriga
 * o'tadi; yozilganda formatlanadi; faqat «+998» qolsa — maydondan chiqqanda va forma yuborilganda bo'sh bo'ladi
 * (ixtiyoriy maydon «+998» bilan yuborilib, xato bermasin).
 */
export function setupPhoneInputs() {
  if (phoneInputsReady) return;
  phoneInputsReady = true;
  const isPhone = (el) => el instanceof HTMLInputElement && el.type === 'tel';
  document.addEventListener('focusin', (e) => {
    const el = e.target;
    if (!isPhone(el)) return;
    const has = phoneParts(el.value).local;
    el.value = has ? phoneInputValue(el.value) : PHONE_PREFIX;
    phonePrev.set(el, el.value);
    phoneTyped.set(el, has ? null : '');
    requestAnimationFrame(() => {
      if (document.activeElement !== el) return;
      const n = el.value.length;
      try { el.setSelectionRange(n, n); } catch { /* */ }
    });
  });
  document.addEventListener('input', (e) => { if (isPhone(e.target)) formatPhoneField(e.target, e.inputType); });
  document.addEventListener('focusout', (e) => {
    const el = e.target;
    if (isPhone(el) && !phoneParts(el.value).local) el.value = '';
  });
  document.addEventListener('submit', (e) => {
    const form = e.target;
    if (!(form instanceof HTMLFormElement)) return;
    form.querySelectorAll('input[type=tel]').forEach(el => { if (!phoneParts(el.value).local) el.value = ''; });
  }, true);
}
export function initials(name) {
  return (name || '?').split(/\s+/).filter(Boolean).slice(0, 2).map(s => s[0]).join('').toUpperCase();
}
export function avatar(url, name, cls = '') {
  return url
    ? `<img class="avatar ${cls}" src="${esc(url)}" alt="">`
    : `<span class="avatar ${cls}" aria-hidden="true">${esc(initials(name))}</span>`;
}
export function stars(r, count) {
  const full = Math.round(r || 0);
  let s = '';
  for (let i = 1; i <= 5; i++) s += i <= full ? '★' : '<span class="off">★</span>';
  return `<span class="stars" aria-label="Reyting ${r || 0} / 5">${s}</span>` +
    (count !== undefined ? ` <b class="num">${(r || 0).toFixed(1)}</b> <span class="muted small">(${count})</span>` : '');
}

// ---- Belgilar ----
export const CARGO = {
  GENERAL: 'Umumiy yuk', PARCEL: 'Posilka', DOCUMENTS: 'Hujjatlar', FOOD: 'Oziq-ovqat', PRODUCE: 'Meva-sabzavot',
  FURNITURE: 'Mebel', HOUSEHOLD: "Uy ko'chirish", APPLIANCES: 'Maishiy texnika', BUILDING_MATERIALS: 'Qurilish materiallari',
  EQUIPMENT: 'Uskuna va texnika', FRAGILE: "Mo'rt yuk", LIVESTOCK: 'Chorva', OTHER: 'Boshqa',
};
/** Mashina turi — faqat 6 tasi (avvalgi «Tentli», «Refrijerator»... endi «Yuk mashinasi») */
export const BODY = {
  TRUCK: 'Yuk mashinasi', SEDAN: 'Yengil mashina', BORT: 'Ochiq bortli', FURGON: 'Furgon',
  CONTAINER: 'Konteyner tashuvchi', OTHER: 'Boshqa',
};
const LEGACY_BODY = {
  MINIVAN: 'FURGON', TENT: 'TRUCK', REFRIGERATOR: 'TRUCK', ISOTHERM: 'TRUCK', SAMOSVAL: 'TRUCK', TRAL: 'TRUCK', TANKER: 'TRUCK',
};
/** Eski qiymat bo'lsa ham hozirgi 6 turdan biri */
export const bodyCanon = (b) => (b && BODY[b] ? b : LEGACY_BODY[b] || (b ? 'OTHER' : ''));
export const bodyLabel = (b) => (b ? BODY[bodyCanon(b)] : '');

/** E'lon turi */
export const KIND = { CARGO: 'Yuk', PARCEL: 'Pochta', PASSENGER: "Yo'lovchi" };
export const KIND_ICON = { CARGO: 'box', PARCEL: 'mail', PASSENGER: 'users' };
export const kindOf = (o) => (o && KIND[o.kind] ? o.kind : 'CARGO');
/** Ro'yxat va sarlavhadagi nomi: «2 kishi», «Pochta: hujjatlar», «20 qop sement» */
export function headline(o) {
  const k = kindOf(o);
  if (k === 'PASSENGER') return `${o.seats || 1} kishi`;
  if (k === 'PARCEL') return !o.title || o.title === 'Pochta' ? 'Pochta' : o.title;
  return o.title;
}
export function kindTag(o) {
  const k = kindOf(o);
  return `<span class="kind-tag k-${k}">${icon(KIND_ICON[k])}${esc(KIND[k])}</span>`;
}
/** Reys vaqti: «Har kuni, 07:00» yoki «Ertaga, 07:00», «5-noyabr, 07:00» (departAt — Toshkent vaqti, "YYYY-MM-DDTHH:mm") */
export function tripWhen(t) {
  const at = String(t.departAt || '');
  const time = at.slice(11, 16);
  return t.daily ? `Har kuni, ${time}` : `${dayWord(at.slice(0, 10))}, ${time}`;
}
/** «Bugun», «Ertaga», «Indinga» yoki «5-noyabr» (YYYY-MM-DD) */
export function dayWord(iso) {
  if (!iso) return '';
  const t = new Date(); t.setHours(0, 0, 0, 0);
  const d = new Date(iso + 'T00:00:00');
  const diff = Math.round((d - t) / 86400000);
  if (diff === 0) return 'Bugun';
  if (diff === 1) return 'Ertaga';
  if (diff === 2) return 'Indinga';
  if (diff === -1) return 'Kecha';
  return date(iso + 'T00:00:00');
}
export const STATUS = {
  NEW: 'Haydovchi kutilmoqda', ACCEPTED: 'Haydovchi tanlandi', IN_TRANSIT: "Yo'lda", DELIVERED: 'Yetkazildi', CANCELLED: 'Bekor qilingan',
};
export const SCOPE = { CITY: 'Shahar ichida', INTERCITY: 'Shahardan shaharga', INTERREGION: 'Viloyatlararo' };
export const PAYMENT = { CASH: 'Naqd', CARD: "Kartaga o'tkazma", TRANSFER: "Bank orqali (pul o'tkazish)" };
export const PAYER = { SENDER: "Yuboruvchi to'laydi", RECEIVER: "Qabul qiluvchi to'laydi" };
export const ROLE = { USER: 'Foydalanuvchi', ADMIN: 'Admin', OWNER: 'Ega' };
export const USTATUS = { ACTIVE: 'Faol', BLOCKED: 'Bloklangan', REMOVED: 'Chiqarilgan' };

export const statusPill = (s) => `<span class="pill st-${s}">${esc(STATUS[s] || s)}</span>`;
export const options = (map, selected, empty) =>
  (empty !== undefined ? `<option value="">${esc(empty)}</option>` : '') +
  Object.entries(map).map(([k, v]) => `<option value="${k}" ${k === selected ? 'selected' : ''}>${esc(v)}</option>`).join('');

export function routeStrip(o, { large = false } = {}) {
  const mid = [o.distanceKm ? km(o.distanceKm) : '', o.durationMin ? duration(o.durationMin) : ''].filter(Boolean).join(', ');
  return `<div class="route ${large ? 'route-lg' : ''}">
    <div class="route-end"><b>${esc(o.from.regionName)}</b><span>${esc(o.from.districtName || o.from.address || '')}</span></div>
    <div class="road" aria-hidden="true"><i></i>${mid ? `<em>${esc(mid)}</em>` : ''}</div>
    <div class="route-end to"><b>${esc(o.to.regionName)}</b><span>${esc(o.to.districtName || o.to.address || '')}</span></div>
  </div>`;
}

// ---- Ikonkalar (inline SVG) ----
const P = {
  bell: '<path d="M6 8a6 6 0 1 1 12 0c0 7 3 9 3 9H3s3-2 3-9"/><path d="M10.3 21a1.94 1.94 0 0 0 3.4 0"/>',
  swap: '<path d="M7 16V4M7 4 3 8M7 4l4 4"/><path d="M17 8v12m0 0 4-4m-4 4-4-4"/>',
  market: '<path d="M3 7h18M3 7l2-4h14l2 4M5 7v13h14V7"/><path d="M9 11h6"/>',
  truck: '<path d="M2 6h11v10H2zM13 9h5l4 4v3h-9"/><circle cx="6.5" cy="17.5" r="2"/><circle cx="17.5" cy="17.5" r="2"/>',
  plus: '<path d="M12 5v14M5 12h14"/>',
  box: '<path d="M21 8 12 3 3 8v8l9 5 9-5z"/><path d="m3 8 9 5 9-5M12 13v8"/>',
  user: '<circle cx="12" cy="8" r="4"/><path d="M4 21a8 8 0 0 1 16 0"/>',
  camera: '<path d="M4 7h3l2-3h6l2 3h3v13H4z"/><circle cx="12" cy="13" r="4"/>',
  phone: '<path d="M22 16.9v3a2 2 0 0 1-2.2 2 19.8 19.8 0 0 1-8.6-3.1 19.5 19.5 0 0 1-6-6A19.8 19.8 0 0 1 2.1 4.2 2 2 0 0 1 4.1 2h3a2 2 0 0 1 2 1.7c.1 1 .4 1.9.7 2.8a2 2 0 0 1-.5 2.1L8.1 9.9a16 16 0 0 0 6 6l1.3-1.3a2 2 0 0 1 2.1-.4c.9.3 1.8.6 2.8.7a2 2 0 0 1 1.7 2z"/>',
  nav: '<path d="m3 11 19-9-9 19-2-8z"/>',
  locate: '<circle cx="12" cy="12" r="3"/><path d="M12 2v3M12 19v3M2 12h3M19 12h3"/><circle cx="12" cy="12" r="8"/>',
  close: '<path d="M18 6 6 18M6 6l12 12"/>',
  shield: '<path d="M12 22s8-4 8-10V5l-8-3-8 3v7c0 6 8 10 8 10z"/>',
  map: '<path d="M9 4 3 6v14l6-2 6 2 6-2V4l-6 2z"/><path d="M9 4v14M15 6v14"/>',
  list: '<path d="M8 6h13M8 12h13M8 18h13M3 6h.01M3 12h.01M3 18h.01"/>',
  telegram: '<path d="m22 3-20 8 7 2 2 7 4-5 5 4z"/><path d="m9 13 13-10"/>',
  logout: '<path d="M9 21H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h4M16 17l5-5-5-5M21 12H9"/>',
  edit: '<path d="M12 20h9"/><path d="M16.5 3.5a2.1 2.1 0 0 1 3 3L7 19l-4 1 1-4z"/>',
  back: '<path d="M15 18 9 12l6-6"/>',
  route: '<circle cx="6" cy="19" r="3"/><path d="M9 19h8.5a3.5 3.5 0 0 0 0-7h-11a3.5 3.5 0 0 1 0-7H15"/><circle cx="18" cy="5" r="3"/>',
  check: '<path d="M20 6 9 17l-5-5"/>',
  verified: '<path d="m12 2 2.4 2.1 3.2-.3.9 3.1 2.8 1.6-1.2 3 1.2 3-2.8 1.6-.9 3.1-3.2-.3L12 22l-2.4-2.1-3.2.3-.9-3.1-2.8-1.6 1.2-3-1.2-3 2.8-1.6.9-3.1 3.2.3z"/><path d="m8.5 12 2.5 2.5 4.5-5"/>',
  copy: '<rect x="9" y="9" width="12" height="12" rx="2"/><path d="M5 15V5a2 2 0 0 1 2-2h10"/>',
  chat: '<path d="M21 11.5a8.4 8.4 0 0 1-12.3 7.5L3 21l2-5.3A8.5 8.5 0 1 1 21 11.5z"/>',
  share: '<circle cx="18" cy="5" r="3"/><circle cx="6" cy="12" r="3"/><circle cx="18" cy="19" r="3"/><path d="m8.6 13.5 6.8 4M15.4 6.5l-6.8 4"/>',
  gift: '<path d="M20 12v10H4V12M2 7h20v5H2zM12 22V7"/><path d="M12 7H7.5a2.5 2.5 0 1 1 0-5C11 2 12 7 12 7zM12 7h4.5a2.5 2.5 0 1 0 0-5C13 2 12 7 12 7z"/>',
  qr: '<path d="M3 3h7v7H3zM14 3h7v7h-7zM3 14h7v7H3z"/><path d="M14 14h3v3h-3zM20 14v.01M14 20h.01M17 17h4v4h-4"/>',
  megaphone: '<path d="M3 11v2a1 1 0 0 0 1 1h3l5 4V6L7 10H4a1 1 0 0 0-1 1z"/><path d="M16 9a4 4 0 0 1 0 6M19 6a8 8 0 0 1 0 12"/>',
  file: '<path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z"/><path d="M14 2v6h6"/>',
  sun: '<circle cx="12" cy="12" r="4"/><path d="M12 2v2M12 20v2M4.9 4.9l1.4 1.4M17.7 17.7l1.4 1.4M2 12h2M20 12h2M4.9 19.1l1.4-1.4M17.7 6.3l1.4-1.4"/>',
  moon: '<path d="M21 12.8A9 9 0 1 1 11.2 3a7 7 0 0 0 9.8 9.8z"/>',
  mail: '<rect x="3" y="5" width="18" height="14" rx="2"/><path d="m3 7 9 6 9-6"/>',
  users: '<circle cx="9" cy="8" r="3.5"/><path d="M2.5 20a6.5 6.5 0 0 1 13 0"/><path d="M16 4.6a3.5 3.5 0 0 1 0 6.8M18.5 14.2a6.5 6.5 0 0 1 3 5.8"/>',
  calendar: '<rect x="3" y="5" width="18" height="16" rx="2"/><path d="M3 10h18M8 3v4M16 3v4"/>',
  clock: '<circle cx="12" cy="12" r="9"/><path d="M12 7v5l3 2"/>',
  settings: '<circle cx="12" cy="12" r="3"/><path d="M19.4 15a1.65 1.65 0 0 0 .33 1.82l.06.06a2 2 0 0 1 0 2.83 2 2 0 0 1-2.83 0l-.06-.06a1.65 1.65 0 0 0-1.82-.33 1.65 1.65 0 0 0-1 1.51V21a2 2 0 0 1-2 2 2 2 0 0 1-2-2v-.09A1.65 1.65 0 0 0 9 19.4a1.65 1.65 0 0 0-1.82.33l-.06.06a2 2 0 0 1-2.83 0 2 2 0 0 1 0-2.83l.06-.06a1.65 1.65 0 0 0 .33-1.82 1.65 1.65 0 0 0-1.51-1H3a2 2 0 0 1-2-2 2 2 0 0 1 2-2h.09A1.65 1.65 0 0 0 4.6 9a1.65 1.65 0 0 0-.33-1.82l-.06-.06a2 2 0 0 1 0-2.83 2 2 0 0 1 2.83 0l.06.06a1.65 1.65 0 0 0 1.82.33H9a1.65 1.65 0 0 0 1-1.51V3a2 2 0 0 1 2-2 2 2 0 0 1 2 2v.09a1.65 1.65 0 0 0 1 1.51 1.65 1.65 0 0 0 1.82-.33l.06-.06a2 2 0 0 1 2.83 0 2 2 0 0 1 0 2.83l-.06.06a1.65 1.65 0 0 0-.33 1.82V9a1.65 1.65 0 0 0 1.51 1H21a2 2 0 0 1 2 2 2 2 0 0 1-2 2h-.09a1.65 1.65 0 0 0-1.51 1z"/>',
  chevron: '<path d="m9 6 6 6-6 6"/>',
  car: '<path d="M5 17h14M3 13l2-6h14l2 6v4H3z"/><circle cx="7.5" cy="17" r="1.8"/><circle cx="16.5" cy="17" r="1.8"/>',
  eye: '<path d="M2 12s3.5-7 10-7 10 7 10 7-3.5 7-10 7S2 12 2 12z"/><circle cx="12" cy="12" r="3"/>',
  pin: '<path d="M12 22s7-6.2 7-12a7 7 0 0 0-14 0c0 5.8 7 12 7 12z"/><circle cx="12" cy="10" r="2.5"/>',
  refresh: '<path d="M21 12a9 9 0 0 1-15.5 6.2L3 16"/><path d="M3 12a9 9 0 0 1 15.5-6.2L21 8"/><path d="M21 3v5h-5M3 21v-5h5"/>',
};

/** "Tekshirilgan haydovchi" belgisi (hujjatlari admin tomonidan tekshirilgan) */
export function verifiedBadge(v, { short = false } = {}) {
  if (!v) return '';
  return `<span class="vbadge" title="Haydovchilik guvohnomasi va texnik pasporti YukGo tomonidan tekshirilgan">${icon('verified')}${short ? '' : '<span>Tekshirilgan</span>'}</span>`;
}

/** Matnni nusxalash (eski brauzer va Telegram WebView uchun zaxira usul bilan) */
export async function copyText(text) {
  try {
    await navigator.clipboard.writeText(text);
    return true;
  } catch {
    const ta = document.createElement('textarea');
    ta.value = text;
    ta.setAttribute('readonly', '');
    ta.style.position = 'fixed';
    ta.style.opacity = '0';
    document.body.appendChild(ta);
    ta.select();
    let ok = false;
    try { ok = document.execCommand('copy'); } catch { /* */ }
    ta.remove();
    return ok;
  }
}
export function icon(name, extra = '') {
  return `<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true" ${extra}>${P[name] || ''}</svg>`;
}

// ---- Toast ----
export function toast(message, kind = '') {
  const root = $('#toast-root');
  const el = document.createElement('div');
  el.className = 'toast ' + kind;
  el.textContent = message;
  root.appendChild(el);
  setTimeout(() => el.remove(), kind === 'err' ? 5000 : 3200);
}

// ---- Modal ----
/** onClose — oyna yopilganda (masalan, yozishmaning yangilanishini to'xtatish uchun) */
export function modal(title, bodyHtml, { onMount, wide, onClose } = {}) {
  const root = $('#modal-root');
  const back = document.createElement('div');
  back.className = 'modal-back';
  back.innerHTML = `<div class="modal" role="dialog" aria-modal="true" aria-label="${esc(title)}" style="${wide ? 'max-width:760px' : ''}">
    <div class="modal-head"><h2>${esc(title)}</h2>
      <button class="btn btn-ghost btn-sm" data-close aria-label="Yopish">${icon('close')}</button></div>
    <div class="modal-body">${bodyHtml}</div></div>`;
  const prevFocus = document.activeElement;
  const close = () => {
    back.remove();
    document.removeEventListener('keydown', onKey);
    if (onClose) { try { onClose(); } catch { /* */ } }
    if (prevFocus && prevFocus.focus) prevFocus.focus();
  };
  const onKey = (e) => { if (e.key === 'Escape') close(); };
  back.addEventListener('click', (e) => { if (e.target === back || e.target.closest('[data-close]')) close(); });
  document.addEventListener('keydown', onKey);
  root.appendChild(back);
  const first = back.querySelector('input, select, textarea, button:not([data-close])');
  if (first) first.focus();
  const ctx = { el: back.querySelector('.modal'), close };
  if (onMount) onMount(ctx);
  return ctx;
}

export function confirmDialog(title, text, { okText = 'Tasdiqlash', danger = false, withReason = false, reasonLabel = 'Sabab (ixtiyoriy)' } = {}) {
  return new Promise((resolve) => {
    let done = false;
    const m = modal(title, `<div class="stack">
      <p>${esc(text)}</p>
      ${withReason ? `<label class="field"><span>${esc(reasonLabel)}</span><textarea class="textarea" name="reason" maxlength="300"></textarea></label>` : ''}
      <div class="row" style="justify-content:flex-end">
        <button class="btn" data-close>Bekor qilish</button>
        <button class="btn ${danger ? 'btn-danger' : 'btn-primary'}" data-ok>${esc(okText)}</button>
      </div></div>`, {
      onMount: ({ el, close }) => {
        el.querySelector('[data-ok]').addEventListener('click', () => {
          done = true;
          const reason = withReason ? el.querySelector('[name=reason]').value.trim() : '';
          close();
          resolve({ reason: reason || null });
        });
        const obs = new MutationObserver(() => {
          if (!document.body.contains(el)) { obs.disconnect(); if (!done) resolve(false); }
        });
        obs.observe(document.getElementById('modal-root'), { childList: true });
      },
    });
    void m;
  });
}

// ---- Forma yordamchilari ----
export function formData(form) {
  const o = {};
  for (const el of form.elements) {
    if (!el.name || el.disabled) continue;
    if (el.type === 'checkbox') o[el.name] = el.checked;
    else if (el.type === 'number') o[el.name] = el.value === '' ? null : Number(el.value);
    else o[el.name] = el.value.trim() === '' ? null : el.value.trim();
  }
  return o;
}

export function showError(form, err) {
  let box = form.querySelector('.form-error');
  if (!box) {
    box = document.createElement('div');
    box.className = 'form-error';
    box.setAttribute('role', 'alert');
    form.prepend(box);
  }
  box.textContent = err?.message || String(err);
  $$('.invalid', form).forEach(el => el.classList.remove('invalid'));
  if (err?.data?.fields) {
    for (const f of Object.keys(err.data.fields)) {
      const el = form.querySelector(`[name="${f}"]`);
      if (el) el.classList.add('invalid');
    }
  }
  box.scrollIntoView({ block: 'nearest', behavior: 'smooth' });
}
export function clearError(form) { form.querySelector('.form-error')?.remove(); }

/** Tugmani so'rov davomida bloklash */
export async function busy(btn, fn) {
  const old = btn.innerHTML;
  btn.disabled = true;
  btn.innerHTML = '<span class="waiting"></span>';
  try { return await fn(); } finally { btn.disabled = false; btn.innerHTML = old; }
}

/**
 * Viloyat → tuman bog'langan selectlar.
 * regionLabel / districtLabel — bo'sh tanlov matni (masalan, qidiruvda «Hamma viloyatlar», «Barcha tumanlar»).
 * Qaytaradi: viloyatlar ro'yxati; selectlarda .setPlace(regionId, districtId) — qiymatni dasturdan qo'yish.
 */
export async function bindRegionDistrict(regionSel, districtSel, {
  regionId, districtId, allowEmptyDistrict = false, regionLabel = 'Viloyatni tanlang', districtLabel,
} = {}) {
  const list = await regions();
  const dLabel = districtLabel || (allowEmptyDistrict ? 'Tuman (ixtiyoriy)' : 'Tumanni tanlang');
  const fillRegions = (selected) => {
    regionSel.innerHTML = `<option value="">${esc(regionLabel)}</option>` +
      list.map(r => `<option value="${r.id}" ${String(r.id) === String(selected ?? '') ? 'selected' : ''}>${esc(r.name)}</option>`).join('');
  };
  let seq = 0;
  async function fillDistricts(selectedId) {
    const my = ++seq;
    const rid = Number(regionSel.value);
    // Avvalgi viloyatning tumani bir lahza ham tanlangan bo'lib qolmasin (ro'yxat yuklanguncha)
    districtSel.innerHTML = `<option value="">${esc(dLabel)}</option>`;
    districtSel.disabled = !rid;
    const ds = await districts(rid);
    if (my !== seq) return;   // shu orada boshqa viloyat tanlandi
    districtSel.innerHTML = `<option value="">${esc(dLabel)}</option>` +
      ds.map(d => `<option value="${d.id}" ${String(d.id) === String(selectedId ?? '') ? 'selected' : ''}>${esc(d.name)}</option>`).join('');
  }
  fillRegions(regionId);
  regionSel.addEventListener('change', () => fillDistricts(null));
  await fillDistricts(districtId);
  regionSel.setPlace = async (r, d) => { fillRegions(r); await fillDistricts(d); };
  return list;
}

/**
 * «Qayerdan → Qayerga» qidiruvi: viloyat, keyin tuman. form ichida fromRegion, fromDistrict, toRegion, toDistrict
 * selectlari va #swap tugmasi bo'lsin. values() — {fromRegion, fromDistrict, toRegion, toDistrict} (bo'sh — '').
 */
export async function bindRouteSearch(form, { from = {}, to = {}, onChange } = {}) {
  const opts = (p, side) => ({
    regionId: p.regionId, districtId: p.districtId, allowEmptyDistrict: true,
    regionLabel: side === 'from' ? 'Qayerdan: hamma viloyatlar' : 'Qayerga: hamma viloyatlar',
    districtLabel: 'Barcha tumanlar',
  });
  await Promise.all([
    bindRegionDistrict(form.fromRegion, form.fromDistrict, opts(from, 'from')),
    bindRegionDistrict(form.toRegion, form.toDistrict, opts(to, 'to')),
  ]);
  const values = () => ({
    fromRegion: form.fromRegion.value, fromDistrict: form.fromDistrict.value,
    toRegion: form.toRegion.value, toDistrict: form.toDistrict.value,
  });
  const swap = form.querySelector('#swap');
  if (swap) swap.addEventListener('click', async () => {
    const v = values();
    await Promise.all([
      form.fromRegion.setPlace(v.toRegion, v.toDistrict),
      form.toRegion.setPlace(v.fromRegion, v.fromDistrict),
    ]);
    if (onChange) onChange(values());
  });
  if (onChange) {
    for (const el of [form.fromRegion, form.fromDistrict, form.toRegion, form.toDistrict]) {
      el.addEventListener('change', () => setTimeout(() => onChange(values()), 0));
    }
  }
  return { values };
}

export async function regionOptions(selected, emptyLabel = 'Barcha viloyatlar') {
  const list = await regions();
  return `<option value="">${esc(emptyLabel)}</option>` +
    list.map(r => `<option value="${r.id}" ${String(r.id) === String(selected ?? '') ? 'selected' : ''}>${esc(r.name)}</option>`).join('');
}

export function emptyState(title, text, actionHtml = '') {
  return `<div class="empty">
    <svg viewBox="0 0 64 40" fill="none" aria-hidden="true"><path d="M4 30h56" stroke="currentColor" stroke-width="3" stroke-dasharray="8 6"/><circle cx="8" cy="14" r="5" fill="currentColor"/><circle cx="56" cy="14" r="5" stroke="currentColor" stroke-width="3"/></svg>
    <h3>${esc(title)}</h3><p class="muted">${esc(text)}</p>${actionHtml}</div>`;
}

export function skeletons(n = 4) {
  return `<div class="stack-sm">${'<div class="skeleton"></div>'.repeat(n)}</div>`;
}
