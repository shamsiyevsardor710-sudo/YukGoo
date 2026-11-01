// E'lonlar: bozor (yuk, pochta, yo'lovchi), yaratish, batafsil ko'rinish, mening buyurtmalarim
import { auth, get, post, del, upload, regions, config } from '../api.js';
import {
  $, $$, esc, icon, money, km, duration, date, dateTime, ago, phoneFmt, avatar, stars, toast, modal, confirmDialog,
  formData, showError, clearError, busy, bindRegionDistrict, bindRouteSearch, options, routeStrip, statusPill,
  emptyState, skeletons, CARGO, BODY, num, verifiedBadge, KIND, KIND_ICON, kindOf, kindTag, headline, bodyLabel,
  bodyCanon, dayWord, tripWhen,
} from '../ui.js';
import { createMap, pin, drawRoute, reverseGeocode, myPosition, UZ_CENTER } from '../maps.js';
import { go, refreshMe } from '../app.js';
import { digitalSalesBlocked } from '../platform.js';

// =====================================================================
// Bozor (bosh sahifa): kirgan foydalanuvchiga avval o'z tumani, keyin viloyati va yaqin hududlardan
// =====================================================================
export async function marketPage(main, _p, query) {
  const me = auth.user;
  const qid = (k) => (/^\d{1,18}$/.test(query.get(k) || '') ? query.get(k) : '');
  const kinds = ['', ...Object.keys(KIND)];
  const f = {
    kind: kinds.includes(query.get('kind') || '') ? (query.get('kind') || '') : '',
    fromRegion: qid('from'), fromDistrict: qid('fd'), toRegion: qid('to'), toDistrict: qid('td'),
  };
  let page = 0, view = 'list', mapCtx = null;
  const stats = await get('/stats/public').catch(() => null);
  const home = me?.region ? [me.district?.name, me.region.name].filter(Boolean).join(', ') : '';

  main.innerHTML = `<div class="page">
    ${auth.loggedIn ? '' : intro(stats)}
    <div class="page-head">
      <div><h1>E'lonlar</h1><p class="muted">${home
        ? `Avval ${esc(home)} bo'yicha, keyin yaqin hududlardan.`
        : "Yuk, pochta va yo'lovchilar — haydovchi kutayotgan e'lonlar."}</p></div>
      <div class="seg" role="tablist">
        <button class="on" data-view="list">Ro'yxat</button><button data-view="map">Xarita</button>
      </div>
    </div>
    <form class="route-search" id="rs">
      <div class="rs-side">
        <select class="select" name="fromRegion" aria-label="Qayerdan: viloyat"></select>
        <select class="select" name="fromDistrict" aria-label="Qayerdan: tuman"></select>
      </div>
      <button type="button" class="swap-btn" id="swap" aria-label="Yo'nalishni almashtirish">${icon('swap')}</button>
      <div class="rs-side">
        <select class="select" name="toRegion" aria-label="Qayerga: viloyat"></select>
        <select class="select" name="toDistrict" aria-label="Qayerga: tuman"></select>
      </div>
      <button class="btn btn-primary btn-lg" type="submit">Qidirish</button>
    </form>
    <div class="kind-chips" id="kinds" role="group" aria-label="E'lon turi">
      ${kinds.map(k => `<button type="button" data-kind="${k}" class="${k === f.kind ? 'on' : ''}" aria-pressed="${k === f.kind}">${k ? icon(KIND_ICON[k]) + esc(KIND[k]) : 'Hammasi'}</button>`).join('')}
    </div>
    <div id="results" style="margin-top:16px">${skeletons()}</div>
    <div id="map-view" class="hidden" style="margin-top:16px"><div class="map map-tall" id="market-map"></div></div>
    <div class="row" style="justify-content:center;margin-top:16px"><button class="btn hidden" id="more">Yana ko'rsatish</button></div>
  </div>`;

  const rs = $('#rs', main);
  const search = await bindRouteSearch(rs, {
    from: { regionId: f.fromRegion, districtId: f.fromDistrict },
    to: { regionId: f.toRegion, districtId: f.toDistrict },
    onChange: () => reload(),
  });
  rs.addEventListener('submit', (e) => { e.preventDefault(); reload(); });
  $$('#kinds button', main).forEach(b => b.addEventListener('click', () => {
    f.kind = b.dataset.kind;
    $$('#kinds button', main).forEach(x => { x.classList.toggle('on', x === b); x.setAttribute('aria-pressed', String(x === b)); });
    reload();
  }));
  $('#more', main).addEventListener('click', () => load(true));
  $$('[data-view]', main).forEach(b => b.addEventListener('click', () => {
    view = b.dataset.view;
    $$('[data-view]', main).forEach(x => x.classList.toggle('on', x === b));
    $('#results', main).classList.toggle('hidden', view !== 'list');
    $('#map-view', main).classList.toggle('hidden', view !== 'map');
    if (view === 'map') reload();
  }));

  let items = [], loadSeq = 0;
  function reload() {
    Object.assign(f, search.values());
    // Manzil satrida qidiruv saqlanadi (sahifa yangilansa yoki ulashilsa ham shu natija)
    const q = new URLSearchParams();
    if (f.kind) q.set('kind', f.kind);
    if (f.fromRegion) q.set('from', f.fromRegion);
    if (f.fromDistrict) q.set('fd', f.fromDistrict);
    if (f.toRegion) q.set('to', f.toRegion);
    if (f.toDistrict) q.set('td', f.toDistrict);
    try { history.replaceState(history.state, '', '#/' + (q.toString() ? '?' + q : '')); } catch { /* */ }
    page = 0; items = [];
    load(false);
  }
  async function load(more) {
    if (more) page++;
    const my = ++loadSeq;
    const res = $('#results', main);
    if (!more) res.innerHTML = skeletons();
    try {
      const data = await get('/orders', { ...f, page });
      if (my !== loadSeq) return;   // shu orada boshqa qidiruv boshlandi
      items = items.concat(data.items);
      res.innerHTML = items.length
        ? (me?.region ? groupedByPlace(items, me) : groupedByDay(items))
        : emptyState("Bu yo'nalishda hozircha e'lon yo'q", "Boshqa tuman yoki viloyatni tanlang yoki birinchi bo'lib e'lon bering.",
          `<a class="btn btn-primary" href="#/new">E'lon berish</a>`);
      $('#more', main).classList.toggle('hidden', data.page + 1 >= data.totalPages);
      if (view === 'map') showOnMap();
    } catch (e) {
      if (my !== loadSeq) return;
      res.innerHTML = emptyState("E'lonlarni yuklab bo'lmadi", e.message);
    }
  }
  async function showOnMap() {
    const el = $('#market-map', main);
    try {
      if (!mapCtx) mapCtx = await createMap(el, { zoom: 6 });
      const { ymaps, map } = mapCtx;
      map.geoObjects.removeAll();
      const cluster = new ymaps.Clusterer({ preset: 'islands#darkBlueClusterIcons', groupByCoordinates: false });
      const marks = [];
      for (const o of items) {
        let c = o.from.lat ? [o.from.lat, o.from.lng] : null;
        if (!c) {
          const r = (await regions()).find(x => x.id === o.from.regionId);
          if (r) c = [r.lat + (Math.random() - .5) * .08, r.lng + (Math.random() - .5) * .08];
        }
        if (!c) continue;
        const p = new ymaps.Placemark(c, {
          balloonContentHeader: esc(`${KIND[kindOf(o)]}: ${headline(o)}`),
          balloonContentBody: `${esc(o.from.regionName)} → ${esc(o.to.regionName)}<br><b>${esc(money(o.price))}</b>`,
          balloonContentFooter: `<a href="#/orders/${o.id}">Batafsil ko'rish</a>`,
          hintContent: esc(headline(o)),
        }, { preset: o.urgent ? 'islands#redDotIcon' : 'islands#yellowDotIcon' });
        marks.push(p);
      }
      cluster.add(marks);
      map.geoObjects.add(cluster);
      if (marks.length) map.setBounds(cluster.getBounds(), { checkZoomRange: true, zoomMargin: 40 });
    } catch { /* xabar xarita ichida ko'rsatiladi */ }
  }
  reload();
}

function intro(stats) {
  return `<section class="intro">
    <div>
      <h1>Yuk, pochta va yo'lovchi — viloyatdan viloyatga</h1>
      <p>E'lon bering — yo'nalishingizdagi haydovchilarga darhol xabar boradi. Reyting bo'yicha tanlang, yukingizni xaritada jonli kuzating.</p>
      <div class="row"><a class="btn btn-primary btn-lg" href="#/register">Ro'yxatdan o'tish</a><a class="btn btn-lg" href="#/login">Kirish</a></div>
      ${stats ? `<div class="intro-facts">
        <div><b class="num">${num(stats.openOrders)}</b>ochiq e'lon</div>
        <div><b class="num">${num(stats.drivers)}</b>haydovchi</div>
        <div><b class="num">${num(stats.delivered)}</b>bajarilgan buyurtma</div></div>` : ''}
    </div>
    <div class="intro-road" aria-hidden="true">
      <svg viewBox="0 0 320 140" fill="none">
        <path d="M10 110 C 90 110, 120 40, 200 40 S 300 30, 310 30" stroke="#3A4850" stroke-width="18" stroke-linecap="round"/>
        <path d="M10 110 C 90 110, 120 40, 200 40 S 300 30, 310 30" stroke="#F2B705" stroke-width="3" stroke-dasharray="12 10" stroke-linecap="round"/>
        <circle cx="12" cy="110" r="9" fill="#F2B705"/><circle cx="308" cy="30" r="9" fill="#fff"/>
        <g class="truck"><rect x="40" y="76" width="34" height="20" rx="3" fill="#fff"/><rect x="74" y="82" width="14" height="14" rx="2" fill="#C9D3D7"/><circle cx="50" cy="98" r="4" fill="#F2B705"/><circle cx="80" cy="98" r="4" fill="#F2B705"/></g>
      </svg>
    </div>
  </section>`;
}

// ---- Mehmon uchun: olib ketish sanasi bo'yicha guruhlar: Shoshilinch → Bugun → Ertaga → Indinga → sanalar → muddati o'tgan → sanasiz ----
// (backenddagi OrderRepository.market tartibi bilan bir xil, shuning uchun "Yana ko'rsatish" guruhlarni buzmaydi)
const WEEKDAYS = ['yakshanba', 'dushanba', 'seshanba', 'chorshanba', 'payshanba', 'juma', 'shanba'];

/** Brauzerning mahalliy sanasi YYYY-MM-DD ko'rinishida (toISOString UTC beradi — tunda bir kun orqada qoladi). */
export function localISO(d = new Date()) {
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`;
}
function addDays(iso, n) {
  const d = new Date(iso + 'T00:00:00');
  d.setDate(d.getDate() + n);
  return localISO(d);
}
/** Guruh kaliti: 'urgent' | 'today' | 'YYYY-MM-DD' (kelgusi kunlar) | 'late' (sanasi o'tgan) | 'nodate' */
export function dayKey(o, today) {
  if (o.urgent) return 'urgent';
  if (!o.pickupDate) return 'nodate';
  // ISO sanalar matn sifatida ham to'g'ri solishtiriladi
  if (o.pickupDate < today) return 'late';
  return o.pickupDate === today ? 'today' : o.pickupDate;
}
export function dayLabel(key, today) {
  if (key === 'urgent') return 'Shoshilinch';
  if (key === 'today') return 'Bugun';
  if (key === 'late') return "Muddati o'tgan";
  if (key === 'nodate') return 'Sana kelishiladi';
  if (key === addDays(today, 1)) return 'Ertaga';
  if (key === addDays(today, 2)) return 'Indinga';
  const d = new Date(key + 'T00:00:00');
  return `${date(key + 'T00:00:00')}, ${WEEKDAYS[d.getDay()]}`;
}
const dayRank = (k) => (k === 'urgent' ? '0' : k === 'today' ? '1' : k === 'late' ? '8' : k === 'nodate' ? '9' : '2' + k);

export function groupedByDay(items) {
  const today = localISO();
  const groups = new Map();
  for (const o of items) {
    const k = dayKey(o, today);
    if (!groups.has(k)) groups.set(k, []);
    groups.get(k).push(o);
  }
  return [...groups.entries()]
    .sort((a, b) => dayRank(a[0]).localeCompare(dayRank(b[0])))
    .map(([k, list]) => `<section class="day-group">
        <h2 class="day-head${k === 'urgent' ? ' is-urgent' : k === 'late' ? ' is-late' : ''}">${k === 'urgent' ? '🔥 ' : ''}${esc(dayLabel(k, today))}<span>${list.length} ta</span></h2>
        <div class="orders">${list.map(o => orderRow(o)).join('')}</div></section>`)
    .join('');
}

/**
 * Kirgan foydalanuvchi uchun: e'lon chiqqan joy bo'yicha guruhlar — «📍 Kosonsoydan», «Namangan viloyatidan»,
 * keyin boshqa viloyatlar yaqinidan boshlab (server shu tartibda beradi, shuning uchun "Yana ko'rsatish" ham buzmaydi).
 * Har bir qatorda sana («Bugun», «Ertaga»).
 */
export function placeKey(o, me) {
  if (me?.district && o.from.districtId === me.district.id) return 'd';
  if (me?.region && o.from.regionId === me.region.id) return 'r';
  return 'x' + o.from.regionId;
}
export function groupedByPlace(items, me) {
  const groups = new Map();
  for (const o of items) {
    const k = placeKey(o, me);
    if (!groups.has(k)) groups.set(k, { label: placeLabel(k, o, me), list: [] });
    groups.get(k).list.push(o);
  }
  return [...groups.entries()].map(([k, g]) => `<section class="day-group">
      <h2 class="day-head${k === 'd' ? ' is-home' : ''}">${k === 'd' ? icon('pin') : ''}${esc(g.label)}<span>${g.list.length} ta</span></h2>
      <div class="orders">${g.list.map(o => orderRow(o, { when: true })).join('')}</div></section>`).join('');
}
function placeLabel(k, o, me) {
  if (k === 'd') return `${me.district.name}dan`;
  if (k === 'r') return `${me.region.name}dan`;
  return `${o.from.regionName}dan`;
}

/** @param when — qatorda sana yorlig'i («Bugun», «Ertaga»): joy bo'yicha guruhlanganda */
export function orderRow(o, { when = false } = {}) {
  const k = kindOf(o);
  const late = o.status === 'NEW' && o.pickupDate && o.pickupDate < localISO();
  const meta = [
    when && o.pickupDate ? `<span class="when-tag${late ? ' is-late' : ''}">${icon('calendar')}${esc(dayWord(o.pickupDate))}</span>` : '',
    o.distanceKm ? `<span class="only-mobile"><b>${esc(km(o.distanceKm))}</b></span>` : '',
    k === 'CARGO' && o.cargoType && o.cargoType !== 'GENERAL' ? `<span>${esc(CARGO[o.cargoType] || '')}</span>` : '',
    k !== 'PASSENGER' && o.weightKg ? `<span><b>${num(o.weightKg, 1)}</b> kg</span>` : '',
    !when && o.pickupDate ? `<span>${k === 'PASSENGER' ? "Jo'nash" : 'Olib ketish'}: <b>${esc(date(o.pickupDate + 'T00:00:00'))}</b>${late ? ' <em class="late">kechikkan</em>' : ''}</span>` : '',
    k === 'CARGO' && o.requiredBodyType ? `<span>${esc(bodyLabel(o.requiredBodyType))}</span>` : '',
    o.status === 'NEW' && o.offersCount ? `<span><b>${o.offersCount}</b> ta taklif</span>` : '',
    `<span>${esc(ago(o.createdAt))}</span>`,
  ].filter(Boolean).join('');
  return `<a class="order-row" href="#/orders/${o.id}">
    <div class="order-top">
      <div class="order-title">${kindTag(o)}${esc(headline(o))}</div>
      <div class="order-side">${o.status === 'NEW'
        ? `<div class="price ${o.price ? '' : 'nego'}">${esc(money(o.price))}</div>`
        : statusPill(o.status)}${o.urgent ? '<span class="urgent-flag">Shoshilinch</span>' : ''}</div>
    </div>
    ${routeStrip(o)}
    <div class="meta">${meta}</div>
  </a>`;
}

// =====================================================================
// Yangi buyurtma
// =====================================================================
const LAST_KIND = 'yukgo_last_kind';
function lastKind() { try { const k = localStorage.getItem(LAST_KIND); return KIND[k] ? k : null; } catch { return null; } }
function rememberKind(k) { try { localStorage.setItem(LAST_KIND, k); } catch { /* */ } }

/**
 * Botga yozilgan e'londan to'ldirilgan forma: "#/new?bot=1&k=PASSENGER&s=2&fr=1&fd=5&tr=12&w=5000&p=3000000&d=2026-10-23&b=TRUCK&c=FURNITURE&t=..&x=.."
 * Noto'g'ri yoki eskirgan qiymatlar jim e'tiborsiz qoldiriladi.
 */
function prefillFrom(query, today) {
  const q = query instanceof URLSearchParams ? query : new URLSearchParams();
  const id = (k) => (/^\d{1,18}$/.test(q.get(k) || '') ? Number(q.get(k)) : null);
  const positive = (k) => { const v = Number(q.get(k)); return Number.isFinite(v) && v > 0 ? v : null; };
  const d = q.get('d') || '';
  const known = (map, k) => Object.prototype.hasOwnProperty.call(map, k);   // eski WebView'larda Object.hasOwn yo'q
  const b = q.get('b') || '';
  const seats = Math.round(positive('s') || 0);
  const kind = known(KIND, q.get('k') || '') ? q.get('k') : null;
  const okSeats = seats >= 1 && seats <= 30 ? seats : null;
  // Botdagi narx — jami; yo'lovchi formasida narx bir kishi uchun so'raladi (yuborishda kishilar soniga ko'paytiriladi)
  let price = positive('p');
  if (price && kind === 'PASSENGER' && okSeats > 1) price = Math.max(1000, Math.round(price / okSeats / 1000) * 1000);
  return {
    fromBot: q.get('bot') === '1',
    kind,
    seats: okSeats,
    fr: id('fr'), fd: id('fd'), tr: id('tr'), td: id('td'),
    weight: positive('w'), price, negotiable: q.get('n') === '1',
    date: /^\d{4}-\d{2}-\d{2}$/.test(d) && d >= today ? d : today,
    body: b && (bodyCanon(b) !== 'OTHER' || b === 'OTHER') ? bodyCanon(b) : '',
    cargo: known(CARGO, q.get('c') || '') ? q.get('c') : 'GENERAL',
    title: (q.get('t') || '').slice(0, 120),
    description: (q.get('x') || '').slice(0, 1500),
  };
}

/** Turga qarab matnlar */
const FORM_TEXT = {
  CARGO: {
    what: 'Nima yuborasiz?', whatPh: 'Masalan: 20 qop sement, divan va 2 ta shkaf',
    price: "Narx, so'm", note: 'Qavat, lift bormi, yuklashga yordam kerakmi…',
    fromAddr: 'Yukni olish manzili', toAddr: 'Topshirish manzili',
  },
  PARCEL: {
    what: 'Nima yuborasiz?', whatPh: 'Masalan: hujjatlar, kichik quti, dori',
    price: "Narx, so'm", note: "Masalan: qabul qiluvchi avtovokzalda kutib oladi",
    fromAddr: 'Pochtani olish manzili', toAddr: 'Topshirish manzili',
  },
  PASSENGER: {
    what: '', whatPh: '',
    price: "Bir kishi uchun narx, so'm", note: 'Masalan: ertalab 7 da, Kosonsoy bozori oldidan',
    fromAddr: 'Qayerdan olib ketish', toAddr: 'Qayerga borasiz',
  },
};

export async function createOrderPage(main, _p, query) {
  const cfg = await get('/config').catch(() => config());   // narxlar o'zgargan bo'lishi mumkin — keshdan emas, yangisini olamiz
  const today = localISO();
  const pre = prefillFrom(query, today);
  const urgentFee = cfg.pricing?.enabled && cfg.pricing.urgentFee > 0 ? cfg.pricing.urgentFee : 0;
  // Birinchi shoshilinch e'lon bepul (admin yoqqan bo'lsa va hali ishlatilmagan bo'lsa)
  const wallet = urgentFee && cfg.pricing.firstUrgentFree ? await get('/wallet').catch(() => null) : null;
  const urgentFree = !!wallet?.urgentFreeAvailable;
  // Android ilova va Telegram ichida pullik «Shoshilinch» taklif qilinmaydi (do'kon qoidalari) — bepul bo'lsa qoladi
  const urgentHidden = urgentFee > 0 && !urgentFree && digitalSalesBlocked();
  let kind = pre.kind || (pre.fromBot ? 'CARGO' : lastKind()) || 'CARGO';
  const kindBtn = (k, sub) => `<button type="button" role="radio" data-kind="${k}" aria-checked="${k === kind}" class="${k === kind ? 'on' : ''}">
      ${icon(KIND_ICON[k])}<b>${esc(KIND[k])}</b><small>${esc(sub)}</small></button>`;
  main.innerHTML = `<div class="page page-narrow">
    <div class="page-head"><div><h1>Yangi e'lon</h1><p class="muted">Yo'nalishingizdagi haydovchilarga darhol xabar boradi.</p></div></div>
    ${pre.fromBot ? `<div class="prefill-note">${icon('check')}<div><b>Botga yozgan e'loningizdan to'ldirildi.</b> Tekshirib chiqing va «E'lon qilish» ni bosing.</div></div>` : ''}
    <form id="order-form" class="stack" novalidate>
      <div class="kind-switch" role="radiogroup" aria-label="Nima kerak?">
        ${kindBtn('CARGO', "Yuk jo'nataman")}${kindBtn('PARCEL', 'Hujjat, kichik quti')}${kindBtn('PASSENGER', "O'zim boraman")}
      </div>

      <section class="panel stack">
        <div class="place-pick"><span class="place-label">Qayerdan</span>
          <div class="pair"><select class="select" name="fromRegionId" required aria-label="Qayerdan: viloyat"></select>
            <select class="select" name="fromDistrictId" aria-label="Qayerdan: tuman"></select></div></div>
        <div class="place-pick is-to"><span class="place-label">Qayerga</span>
          <div class="pair"><select class="select" name="toRegionId" required aria-label="Qayerga: viloyat"></select>
            <select class="select" name="toDistrictId" aria-label="Qayerga: tuman"></select></div></div>
        <div class="field"><span id="when-label">Qachon</span>
          <div class="day-chips" role="group" aria-labelledby="when-label">
            <button type="button" data-day="0">Bugun</button><button type="button" data-day="1">Ertaga</button><button type="button" data-day="2">Indinga</button>
            <input class="input" type="date" name="pickupDate" min="${today}" value="${pre.date}" aria-label="Boshqa sana"></div></div>
      </section>

      <section class="panel stack" data-for="CARGO PARCEL">
        <label class="field"><span id="what-label">Nima yuborasiz?</span>
          <input class="input" name="title" maxlength="120" value="${esc(pre.title)}"></label>
        <div class="form-grid" data-for="CARGO">
          <label class="field"><span>Og'irligi, kg <span class="muted">(ixtiyoriy)</span></span><input class="input" name="weightKg" type="number" min="0.1" step="0.1" inputmode="decimal" placeholder="500" value="${pre.weight ?? ''}"></label>
          <label class="field"><span>Qanday mashina kerak?</span><select class="select" name="requiredBodyType">${options(BODY, pre.body, "Farqi yo'q")}</select></label>
        </div>
      </section>

      <section class="panel stack" data-for="PASSENGER">
        <div class="field"><span id="seats-label">Necha kishi?</span>
          <div class="stepper" role="group" aria-labelledby="seats-label">
            <button type="button" data-step="-1" aria-label="Kamaytirish">−</button>
            <input class="input" name="seats" type="number" min="1" max="30" value="${pre.seats || 1}" inputmode="numeric" aria-label="Necha kishi">
            <button type="button" data-step="1" aria-label="Ko'paytirish">+</button></div></div>
      </section>

      <section class="panel stack">
        <label class="field"><span><span id="price-label">Narx, so'm</span> <span class="muted">(ixtiyoriy)</span></span>
          <input class="input" name="price" type="number" min="1000" step="1000" inputmode="numeric" placeholder="Masalan: 300000" value="${pre.negotiable ? '' : (pre.price ?? '')}">
          <small id="price-hint">Bo'sh qoldirsangiz — narx kelishiladi.</small></label>
        <div class="panel-sunk hidden" id="estimate"></div>
        <div class="field" data-for="CARGO PARCEL"><span>Kim qabul qiladi?</span>
          <div class="seg" id="receiver-mode"><button type="button" class="on" data-r="me">O'zim</button><button type="button" data-r="other">Boshqa odam</button></div>
          <div class="form-grid hidden" id="receiver-other">
            <label class="field"><span>Telefon raqami</span><input class="input" name="receiverPhone" type="tel" inputmode="tel" placeholder="+998 90 123 45 67" autocomplete="off"></label>
            <label class="field"><span>Ismi <span class="muted">(ixtiyoriy)</span></span><input class="input" name="receiverName" maxlength="120" autocomplete="off"></label>
          </div>
          <small>Yo'lga chiqqach topshirish kodi sizga ko'rinadi — qabul qiluvchi uni haydovchiga aytadi.</small></div>
        <label class="field"><span>Izoh <span class="muted">(ixtiyoriy)</span></span>
          <textarea class="textarea" name="description" maxlength="1500" rows="2">${esc(pre.description)}</textarea></label>
        <details class="more" id="more-box">
          <summary>Aniq manzil va xarita <span class="muted">(ixtiyoriy)</span></summary>
          <div class="stack" style="margin-top:14px">
            <label class="field"><span id="from-addr-label">Olish manzili</span><input class="input" name="fromAddress" maxlength="250" placeholder="Xaritadan belgilang yoki yozing"></label>
            <label class="field"><span id="to-addr-label">Topshirish manzili</span><input class="input" name="toAddress" maxlength="250" placeholder="Xaritadan belgilang yoki yozing"></label>
            <div class="map-tools">
              <div class="seg" id="pick-mode"><button type="button" class="on" data-mode="from">Olish nuqtasi</button><button type="button" data-mode="to">Borish nuqtasi</button></div>
              <button type="button" class="btn btn-sm" id="my-loc">${icon('locate')}Mening joylashuvim</button>
            </div>
            <div class="map" id="pick-map"></div>
            <p class="small muted" id="route-info">Xaritaga bosib nuqtalarni belgilang — masofa avtomatik hisoblanadi.</p>
            <label class="field" data-for="CARGO"><span>Yuk rasmi</span><input class="input" name="photo" type="file" accept="image/jpeg,image/png,image/webp"></label>
          </div>
        </details>
        ${urgentHidden ? '' : `<label class="check"><input type="checkbox" name="urgent"> <span>Shoshilinch — o'z hududidagi e'lonlar ichida eng yuqorida turadi${urgentFee
          ? (urgentFree ? ` <b class="fee fee-free">Bepul</b> <s class="tiny muted">${esc(money(urgentFee))}</s>` : ` <b class="fee">${esc(money(urgentFee))}</b>`) : ''}</span></label>
        ${urgentFee ? `<p class="tiny muted">${urgentFree ? "Birinchi shoshilinch e'loningiz bepul — balansdan hech narsa yechilmaydi."
          : "Shoshilinch narxi e'lon joylanganda balansingizdan yechiladi."}</p>` : ''}`}
      </section>
      <button class="btn btn-primary btn-lg btn-block" type="submit">E'lon qilish</button>
      ${cfg.channelPosting ? `<p class="tiny muted" style="text-align:center">📣 E'loningiz YukGo Telegram kanallarida ham chiqadi — ismingiz va telefoningiz u yerda ko'rsatilmaydi.</p>` : ''}
    </form>
  </div>`;

  const form = $('#order-form', main);
  const titleInput = form.querySelector('[name=title]');   // form.title — formaning o'z "title" xossasi bilan adashmasin
  const me = auth.user;
  await Promise.all([
    bindRegionDistrict(form.fromRegionId, form.fromDistrictId, pre.fr
      ? { regionId: pre.fr, districtId: pre.fd, allowEmptyDistrict: true }
      : { regionId: me?.region?.id, districtId: me?.district?.id, allowEmptyDistrict: true }),
    bindRegionDistrict(form.toRegionId, form.toDistrictId, { regionId: pre.tr, districtId: pre.td, allowEmptyDistrict: true }),
  ]);

  // ---- Tur: yuk, pochta, yo'lovchi ----
  function setKind(k) {
    kind = k;
    const t = FORM_TEXT[k];
    $$('.kind-switch button', main).forEach(b => {
      const on = b.dataset.kind === k;
      b.classList.toggle('on', on);
      b.setAttribute('aria-checked', String(on));
    });
    $$('[data-for]', main).forEach(el => el.classList.toggle('hidden', !el.dataset.for.split(' ').includes(k)));
    if (t.what) {
      $('#what-label', main).innerHTML = esc(t.what) + (k === 'PARCEL' ? ' <span class="muted">(ixtiyoriy)</span>' : '');
      titleInput.placeholder = t.whatPh;
    }
    $('#price-label', main).textContent = t.price;
    form.description.placeholder = t.note;
    $('#from-addr-label', main).textContent = t.fromAddr;
    $('#to-addr-label', main).textContent = t.toAddr;
    $$('#pick-mode button', main)[0].textContent = k === 'PASSENGER' ? 'Olib ketish nuqtasi' : 'Olish nuqtasi';
    updatePriceHint();
  }
  $$('.kind-switch button', main).forEach(b => b.addEventListener('click', () => setKind(b.dataset.kind)));

  // ---- Qachon: Bugun / Ertaga / Indinga yoki sana ----
  const syncDays = () => {
    $$('.day-chips button', main).forEach(b => b.classList.toggle('on', form.pickupDate.value === addDays(today, Number(b.dataset.day))));
  };
  $$('.day-chips button', main).forEach(b => b.addEventListener('click', () => {
    form.pickupDate.value = addDays(today, Number(b.dataset.day));
    syncDays();
  }));
  form.pickupDate.addEventListener('change', syncDays);
  syncDays();

  // ---- Necha kishi ----
  const seatsVal = () => Math.min(30, Math.max(1, Math.round(Number(form.seats.value) || 1)));
  $$('.stepper [data-step]', main).forEach(b => b.addEventListener('click', () => {
    form.seats.value = Math.min(30, Math.max(1, seatsVal() + Number(b.dataset.step)));
    updatePriceHint();
  }));
  form.seats.addEventListener('input', updatePriceHint);

  function updatePriceHint() {
    const hint = $('#price-hint', main);
    const p = Number(form.price.value) || 0;
    if (kind === 'PASSENGER' && p > 0 && seatsVal() > 1) {
      hint.textContent = `Jami: ${money(p * seatsVal())} (${seatsVal()} kishi)`;
    } else {
      hint.textContent = kind === 'CARGO' ? "Bo'sh qoldirsangiz — haydovchilar o'z narxini taklif qiladi."
        : "Bo'sh qoldirsangiz — haydovchi bilan telefonda kelishasiz.";
    }
  }
  form.price.addEventListener('input', updatePriceHint);

  // ---- Qabul qiluvchi: o'zim yoki boshqa odam ----
  let receiverOther = false;
  $$('#receiver-mode button', main).forEach(b => b.addEventListener('click', () => {
    receiverOther = b.dataset.r === 'other';
    $$('#receiver-mode button', main).forEach(x => x.classList.toggle('on', x === b));
    $('#receiver-other', main).classList.toggle('hidden', !receiverOther);
    if (receiverOther) form.receiverPhone.focus();
  }));

  // ---- Xarita bilan aniq nuqta (ixtiyoriy, ochilganda yuklanadi) ----
  const pts = { from: null, to: null };
  let route = { distanceKm: null, durationMin: null };
  let mode = 'from', ctx = null, marks = {}, routeObj = null, mapStarted = false;
  $$('#pick-mode button', main).forEach(b => b.addEventListener('click', () => {
    mode = b.dataset.mode;
    $$('#pick-mode button', main).forEach(x => x.classList.toggle('on', x === b));
  }));
  const startMap = async () => {
    if (mapStarted) return;
    mapStarted = true;
    try {
      const r = (await regions()).find(x => x.id === Number(form.fromRegionId.value));
      ctx = await createMap($('#pick-map', main), { center: r ? [r.lat, r.lng] : UZ_CENTER, zoom: r ? 10 : 6 });
      ctx.map.events.add('click', (e) => setPoint(mode, e.get('coords'), true));
    } catch { /* xabar xaritada */ }
  };
  $('#more-box', main).addEventListener('toggle', (e) => { if (e.currentTarget.open) startMap(); });

  const centerOn = async (sel) => {
    if (!ctx) return;
    const r = (await regions()).find(x => x.id === Number(sel.value));
    if (r) ctx.map.setCenter([r.lat, r.lng], 9, { duration: 300 });
  };
  form.fromRegionId.addEventListener('change', () => { mode = 'from'; centerOn(form.fromRegionId); });
  form.toRegionId.addEventListener('change', () => { if (ctx) $$('#pick-mode button', main)[1].click(); centerOn(form.toRegionId); });

  $('#my-loc', main).addEventListener('click', async () => {
    try {
      const c = await myPosition();
      setPoint(mode, c, true);
      ctx?.map.setCenter(c, 14);
    } catch (e) { toast(e.message, 'err'); }
  });

  async function setPoint(k, coords, fill) {
    if (!ctx) return;
    pts[k] = coords;
    if (marks[k]) ctx.map.geoObjects.remove(marks[k]);
    marks[k] = pin(ctx.ymaps, coords, k, { label: k === 'from' ? 'Olish' : 'Borish', draggable: true });
    marks[k].events.add('dragend', () => setPoint(k, marks[k].geometry.getCoordinates(), true));
    ctx.map.geoObjects.add(marks[k]);
    if (fill) {
      const addr = await reverseGeocode(ctx.ymaps, coords);
      if (addr) form[k === 'from' ? 'fromAddress' : 'toAddress'].value = addr;
    }
    if (k === 'from' && !pts.to) $$('#pick-mode button', main)[1].click();
    if (pts.from && pts.to) {
      if (routeObj) ctx.map.geoObjects.remove(routeObj);
      $('#route-info', main).textContent = 'Marshrut hisoblanmoqda…';
      const r = await drawRoute(ctx.ymaps, ctx.map, [pts.from, pts.to]);
      routeObj = r.route;
      route = r;
      $('#route-info', main).innerHTML = r.distanceKm
        ? `Masofa: <b>${esc(km(r.distanceKm))}</b>, yo'lda taxminan <b>${esc(duration(r.durationMin))}</b>`
        : "Marshrutni hisoblab bo'lmadi, lekin nuqtalar saqlandi.";
      estimate();
    }
  }

  async function estimate() {
    if (!route.distanceKm || kind !== 'CARGO') return;
    const scope = Number(form.fromRegionId.value) !== Number(form.toRegionId.value) ? 'INTERREGION'
      : (form.fromDistrictId.value && form.fromDistrictId.value === form.toDistrictId.value ? 'CITY' : 'INTERCITY');
    try {
      const e = await get('/orders/estimate', { distanceKm: route.distanceKm, weightKg: form.weightKg.value || undefined, scope });
      const box = $('#estimate', main);
      box.classList.remove('hidden');
      box.innerHTML = `<p class="small muted">Shu masofa va og'irlik uchun odatiy narx</p>
        <p style="font-size:22px;font-weight:700" class="num">${esc(money(e.suggested))}</p>
        <p class="small muted">${esc(money(e.min))} dan ${esc(money(e.max))} gacha</p>
        <button type="button" class="btn btn-sm" style="margin-top:8px" id="use-est">Shu narxni qo'yish</button>`;
      $('#use-est', main).addEventListener('click', () => { form.price.value = e.suggested; updatePriceHint(); });
    } catch { /* */ }
  }
  form.weightKg.addEventListener('change', estimate);

  setKind(kind);

  form.addEventListener('submit', async (e) => {
    e.preventDefault();
    clearError(form);
    const d = formData(form);
    if (!d.fromRegionId) return showError(form, { message: 'Qayerdan — viloyatni tanlang' });
    if (!d.toRegionId) return showError(form, { message: 'Qayerga — viloyatni tanlang' });
    if (kind === 'CARGO' && !d.title) { titleInput.focus(); return showError(form, { message: 'Nima yuborayotganingizni yozing' }); }
    if (kind !== 'PASSENGER' && receiverOther && !d.receiverPhone) {
      form.receiverPhone.focus();
      return showError(form, { message: 'Qabul qiluvchining telefon raqamini yozing' });
    }
    const seats = seatsVal();
    const price = d.price ? Math.round(kind === 'PASSENGER' ? d.price * seats : d.price) : null;
    const body = {
      kind,
      title: kind === 'PASSENGER' ? null : d.title,
      seats: kind === 'PASSENGER' ? seats : null,
      weightKg: kind === 'CARGO' ? d.weightKg : null,
      requiredBodyType: kind === 'CARGO' ? (d.requiredBodyType || null) : null,
      cargoType: kind === 'CARGO' && pre.cargo !== 'GENERAL' ? pre.cargo : null,
      description: d.description,
      from: { regionId: Number(d.fromRegionId) || null, districtId: Number(d.fromDistrictId) || null, address: d.fromAddress, lat: pts.from?.[0], lng: pts.from?.[1] },
      to: { regionId: Number(d.toRegionId) || null, districtId: Number(d.toDistrictId) || null, address: d.toAddress, lat: pts.to?.[0], lng: pts.to?.[1] },
      distanceKm: route.distanceKm, durationMin: route.durationMin,
      pickupDate: d.pickupDate || today, price,
      urgent: !!d.urgent,
      receiverName: kind !== 'PASSENGER' && receiverOther ? d.receiverName : null,
      receiverPhone: kind !== 'PASSENGER' && receiverOther ? d.receiverPhone : null,
    };
    try {
      const o = await busy(form.querySelector('[type=submit]'), async () => {
        const created = await post('/orders', body);
        const file = kind === 'CARGO' ? form.photo.files[0] : null;
        if (file) await upload(`/orders/${created.id}/photo`, file).catch(err => toast('Rasm yuklanmadi: ' + err.message, 'err'));
        return created;
      });
      rememberKind(kind);
      toast(kind === 'PASSENGER' ? "E'lon joylandi! Yo'nalishingizdagi haydovchilarga xabar yuborildi"
        : "E'lon joylandi! Haydovchilarga xabar yuborildi", 'ok');
      go('#/orders/' + o.id);
    } catch (err) { showError(form, err); }
  });
}

// =====================================================================
// Buyurtma sahifasi
// =====================================================================
/** Backenddagi WalletService.calcCommission bilan bir xil hisob (faqat ko'rsatish uchun, yechish — serverda). */
export function commissionFor(pr, price) {
  if (!pr?.enabled || !price) return 0;
  let c = Math.max(Math.round(price * pr.commissionPercent / 100), pr.commissionMin || 0);
  if (pr.commissionMax > 0) c = Math.min(c, pr.commissionMax);
  return Math.max(0, Math.round(c / 100) * 100);
}

export async function orderPage(main, { id }) {
  let o, timer = null, chatTimer = null, mapCtx = null, driverMark = null, lastMsgId = 0, routeDrawn = false, needsRender = false;
  const cfg = await get('/config').catch(() => config());   // narxlar o'zgargan bo'lishi mumkin — keshdan emas, yangisini olamiz
  const pr = cfg.pricing?.enabled ? cfg.pricing : null;   // daromad tizimi o'chiq bo'lsa — null, hech narsa ko'rsatilmaydi
  const urgentFee = pr && pr.urgentFee > 0 ? pr.urgentFee : 0;
  // Shaxsiy bonuslar: yangi haydovchining komissiyasiz buyurtmalari va birinchi bepul shoshilinch e'lon
  const myWallet = pr && auth.loggedIn ? await get('/wallet').catch(() => null) : null;
  const freeLeft = myWallet?.freeDeliveriesLeft || 0;
  const urgentFree = urgentFee > 0 && !!myWallet?.urgentFreeAvailable;
  const urgentHidden = urgentFee > 0 && !urgentFree && digitalSalesBlocked();   // do'kon qoidalari (platform.js)

  async function load() {
    o = await get('/orders/' + id);
    render();
  }

  /** E'lon tafsilotlari — turga qarab (yo'lovchida: sana, necha kishi; yukda: og'irlik, mashina, qabul qiluvchi) */
  function detailsHtml(participant) {
    const k = kindOf(o);
    const seats = o.seats || 1;
    const price = o.finalPrice ?? o.price;
    const rows = [];
    if (k === 'PASSENGER') {
      if (o.pickupDate) rows.push(["Jo'nash", `${dayWord(o.pickupDate)}, ${date(o.pickupDate + 'T00:00:00')}`]);
      rows.push(['Necha kishi', `${seats} kishi`]);
      if (price && seats > 1) rows.push(['Bir kishiga', money(Math.round(price / seats))]);
    } else {
      if (k === 'CARGO' && o.cargoType && o.cargoType !== 'GENERAL') rows.push(['Turi', CARGO[o.cargoType]]);
      if (o.weightKg) rows.push(["Og'irligi", `${num(o.weightKg, 1)} kg`]);
      if (k === 'CARGO' && o.requiredBodyType) rows.push(['Mashina', bodyLabel(o.requiredBodyType)]);
      if (o.pickupDate) rows.push(['Olib ketish', `${dayWord(o.pickupDate)}, ${date(o.pickupDate + 'T00:00:00')}`]);
    }
    if (o.distanceKm) rows.push(['Masofa', km(o.distanceKm) + (o.durationMin ? `, ~${duration(o.durationMin)}` : '')]);
    const receiver = participant && k !== 'PASSENGER' && o.receiverPhone
      ? `<dt>Qabul qiluvchi</dt><dd>${o.receiverName ? esc(o.receiverName) + ', ' : ''}<a href="tel:${esc(o.receiverPhone)}">${esc(phoneFmt(o.receiverPhone))}</a></dd>` : '';
    return `<dl class="kv">${rows.map(([a, b]) => `<dt>${esc(a)}</dt><dd>${esc(b)}</dd>`).join('')}${receiver}</dl>`;
  }

  function render() {
    const role = o.viewerRole;
    const isSender = role === 'SENDER', isDriver = role === 'DRIVER';
    const participant = isSender || isDriver;
    const k = kindOf(o);
    const who = k === 'PASSENGER' ? "Yo'lovchi" : k === 'PARCEL' ? "Jo'natuvchi" : 'Yuk egasi';
    main.innerHTML = `<div class="page">
      <div class="page-head">
        <div>
          <div class="row" style="margin-bottom:8px">${kindTag(o)}${statusPill(o.status)}<span class="muted small">#${o.id}, ${esc(ago(o.createdAt))}</span>
            ${o.urgent ? '<span class="urgent-flag">Shoshilinch</span>' : ''}</div>
          <h1>${esc(k === 'PASSENGER' ? `Yo'lovchi: ${o.seats || 1} kishi` : headline(o))}</h1>
        </div>
        <div class="price ${o.finalPrice || o.price ? '' : 'nego'}" style="font-size:24px">${esc(money(o.finalPrice ?? o.price))}</div>
      </div>
      <div class="grid-2">
        <div class="stack">
          <section class="panel stack">
            ${routeStrip(o, { large: true })}
            ${o.from.lat != null || o.to.lat != null || (participant && (o.status === 'ACCEPTED' || o.status === 'IN_TRANSIT'))
              ? '<div class="map" id="order-map"></div>' : ''}
            ${participant && (o.from.address || o.to.address) ? `<dl class="kv small">
              ${o.from.address ? `<dt>${k === 'PASSENGER' ? 'Olib ketish' : 'Olish manzili'}</dt><dd>${esc(o.from.address)}</dd>` : ''}
              ${o.to.address ? `<dt>${k === 'PASSENGER' ? 'Borish manzili' : 'Topshirish manzili'}</dt><dd>${esc(o.to.address)}</dd>` : ''}</dl>` : ''}
          </section>
          <section class="panel">
            <h2>${k === 'PASSENGER' ? 'Safar haqida' : k === 'PARCEL' ? 'Pochta haqida' : 'Yuk haqida'}</h2>
            ${detailsHtml(participant)}
            ${o.description ? `<p style="margin-top:14px;white-space:pre-line">${esc(o.description)}</p>` : ''}
            ${o.photoUrl ? `<img src="${esc(o.photoUrl)}" alt="Yuk rasmi" style="margin-top:14px;border-radius:12px;max-height:320px;object-fit:cover">` : ''}
          </section>
          ${participant && o.driver ? `<section class="panel"><h2>Yozishma</h2>
            <div class="chat" style="margin-top:12px"><div class="chat-list" id="chat-list"></div>
              ${o.block ? `<div class="chat-closed small" role="status">${o.block === 'ME'
                  ? `Siz bu foydalanuvchini bloklagansiz — yozishma yopiq. <button class="link-btn" type="button" data-unblock>Blokdan chiqarish</button>`
                  : "Bu buyurtma bo'yicha yozishma yopilgan."}</div>`
                : `<form class="chat-form" id="chat-form"><input class="input" name="text" maxlength="1000" placeholder="Xabar yozing…" autocomplete="off" aria-label="Xabar">
                <button class="btn btn-route" type="submit">Yuborish</button></form>`}</div></section>` : ''}
          ${participant || auth.isStaff ? `<section class="panel"><h2>Tarix</h2><ul class="timeline" id="timeline" style="margin-top:14px"></ul></section>` : ''}
        </div>
        <div class="stack">
          <div id="route-trips"></div>
          <div id="actions" class="stack"></div>
          <section class="panel stack-sm">
            <h3>${who}</h3>${personCard(o.sender)}
            ${isDriver ? counterpartActions() : ''}
          </section>
          ${o.driver ? `<section class="panel stack-sm"><h3>Haydovchi</h3>${personCard(o.driver, true)}${isSender ? counterpartActions() : ''}</section>` : ''}
          ${!participant && auth.user ? `<button class="btn btn-ghost btn-sm" id="report">Shikoyat qilish</button>` : ''}
        </div>
      </div>
    </div>`;
    renderActions();
    drawMap();
    if (isSender && o.status === 'NEW') loadRouteTrips();
    if ($('#timeline', main)) loadEvents();
    if ($('#chat-list', main)) initChat();
    // Begona e'lon: shikoyat (istasa — yuboruvchini bloklash ham; shunda e'lonlari bozorda ko'rinmaydi)
    $('#report', main)?.addEventListener('click', () => reportDialog(o.sender.id, o.id, { name: o.sender.fullName, onBlocked: () => go('#/') }));
    // Ikkinchi tomon: shikoyat va bloklash (Google Play: 1:1 yozishmada majburiy)
    const other = isSender ? o.driver : isDriver ? o.sender : null;
    if (other) {
      $('[data-report-other]', main)?.addEventListener('click', () => reportDialog(other.id, o.id, { name: other.fullName, onBlocked: load }));
      $('[data-block-other]', main)?.addEventListener('click', () => blockUser(other.id, other.fullName, load));
      $$('[data-unblock]', main).forEach(b => b.addEventListener('click', () => unblockUser(other.id, load)));
    }
  }

  /** Buyurtmadagi ikkinchi tomon uchun: shikoyat qilish va bloklash / blokdan chiqarish */
  function counterpartActions() {
    return `<div class="person-actions">
        <button class="link-btn" type="button" data-report-other>Shikoyat qilish</button>
        ${o.block === 'ME'
          ? `<button class="link-btn" type="button" data-unblock>Blokdan chiqarish</button>`
          : `<button class="link-btn danger-link" type="button" data-block-other>Bloklash</button>`}
      </div>`;
  }

  function personCard(u, withVehicle) {
    // Server raqamni faqat buyurtma tomonlariga yuboradi; boshqalarga sababini tushuntiramiz
    const lockText = o.status === 'NEW' && auth.user?.driver
      ? "Raqam «Olaman» bosilgach yoki taklifingiz qabul qilingach ko'rinadi"
      : "Raqam faqat buyurtma tomonlariga ko'rinadi";
    return `<a class="person" href="#/u/${u.id}">${avatar(u.avatarUrl, u.fullName)}
      <div style="min-width:0"><div class="person-name">${esc(u.fullName)} ${verifiedBadge(u.verified)}</div>
        <div class="person-sub">${stars(u.rating, u.ratingCount)}</div></div></a>
      ${withVehicle && u.vehicle ? `<div class="small muted">${esc(u.vehicle.brand)}${u.vehicle.model ? ' ' + esc(u.vehicle.model) : ''}, ${esc(bodyLabel(u.vehicle.bodyType))}${u.vehicle.color ? ', ' + esc(u.vehicle.color) : ''}
        <span class="plate" style="font-size:13px;margin-left:6px"><span>${esc(u.vehicle.plateNumber)}</span></span></div>` : ''}
      ${u.phone
        ? `<a class="btn btn-sm" href="tel:${esc(u.phone)}">${icon('phone')}${esc(phoneFmt(u.phone))}</a>`
        : `<p class="phone-lock">${icon('shield')}${esc(lockText)}</p>`}`;
  }

  function renderActions() {
    const box = $('#actions', main);
    const me = auth.user;
    const role = o.viewerRole;
    const k = kindOf(o);
    const passenger = k === 'PASSENGER';
    let html = '';

    if (role === 'SENDER') {
      if (o.status === 'NEW') {
        html += `<section class="panel"><div class="row-between"><h2>Takliflar</h2><span class="muted small">${o.offersCount} ta</span></div><div id="offers">${skeletons(2)}</div></section>`;
        const late = o.pickupDate && o.pickupDate < localISO();   // sanasi o'tgan e'lonni server shoshilinch qilmaydi
        if (late) {
          html += `<section class="panel stack-sm"><h2>${passenger ? "Jo'nash" : 'Olib ketish'} sanasi o'tdi</h2>
            <p class="small muted">Sanasi o'tganiga 7 kun bo'lgach e'lon avtomatik yopiladi. Hali kerak bo'lsa, bu e'lonni bekor qilib, yangi sana bilan qayta joylang.</p></section>`;
        } else if (!o.urgent && !urgentHidden) {
          html += `<section class="panel stack-sm"><h2>Tezroq haydovchi topish</h2>
            <p class="small muted">Shoshilinch e'lon o'z hududidagi e'lonlar ichida eng yuqorida turadi va haydovchilarga qayta xabar boradi.</p>
            <button class="btn btn-primary btn-block" id="make-urgent">🔥 Shoshilinch qilish${urgentFee ? ' — ' + (urgentFree ? 'bepul' : esc(money(urgentFee))) : ''}</button></section>`;
        }
      }
      if (!passenger && (o.status === 'ACCEPTED' || o.status === 'IN_TRANSIT')) {
        html += `<div class="code-card"><p>Topshirish kodi</p><span class="code">${esc(o.deliveryCode)}</span>
          <p>Bu kodni faqat qabul qiluvchiga ayting. Haydovchi topshirganda shu kodni kiritadi.</p></div>`;
      }
      if (passenger && o.status === 'ACCEPTED') {
        html += `<section class="panel stack-sm"><h2>Haydovchi topildi</h2>
          <p class="small muted">Haydovchi bilan telefonda kelishib oling: qayerda va qachon kutib turasiz. Mashinasi va raqami pastda.</p></section>`;
      }
      if (o.status === 'NEW' || o.status === 'ACCEPTED') {
        html += `<button class="btn btn-danger btn-block" id="cancel">${passenger ? "E'lonni bekor qilish" : 'Buyurtmani bekor qilish'}</button>`;
      }
    } else if (role === 'DRIVER') {
      if (o.status === 'ACCEPTED') {
        html += passenger
          ? `<section class="panel stack-sm"><h2>Yo'lovchini olishga boring</h2>
            <p class="small muted">Yo'lovchiga qo'ng'iroq qilib, qayerda kutishini kelishib oling.</p>
            <a class="btn btn-route btn-lg btn-block" href="#/nav/${o.id}">${icon('nav')}Navigatsiyani boshlash</a>
            <button class="btn btn-primary btn-lg btn-block" id="pickup">Yo'lovchini oldim, yo'lga chiqdik</button>
            <button class="btn btn-danger btn-block" id="cancel">Voz kechish</button>`
          : `<section class="panel stack-sm"><h2>${k === 'PARCEL' ? 'Pochtani' : 'Yukni'} olishga boring</h2>
            <p class="small muted">Navigatsiya olish manziligacha yo'l ko'rsatadi, yuboruvchi esa sizni xaritada kuzatadi.</p>
            <a class="btn btn-route btn-lg btn-block" href="#/nav/${o.id}">${icon('nav')}Navigatsiyani boshlash</a>
            <button class="btn btn-primary btn-lg btn-block" id="pickup">${k === 'PARCEL' ? 'Pochtani' : 'Yukni'} oldim, yo'lga chiqdim</button>
            <button class="btn btn-danger btn-block" id="cancel">Voz kechish</button>`;
        html += `${o.commission ? `<p class="tiny muted">Bu buyurtma uchun balansingizdan ${esc(money(o.commission))} komissiya yechilgan.</p>` : ''}</section>`;
      }
      if (o.status === 'IN_TRANSIT') {
        html += passenger
          ? `<section class="panel stack-sm"><h2>Yo'ldasiz</h2>
            <a class="btn btn-route btn-lg btn-block" href="#/nav/${o.id}">${icon('nav')}Navigatsiya: borish manziliga</a>
            <button class="btn btn-ok btn-lg btn-block" id="arrived">Manzilga yetib keldik</button></section>`
          : `<section class="panel stack-sm"><h2>${k === 'PARCEL' ? 'Pochta' : 'Yuk'} yo'lda</h2>
            <a class="btn btn-route btn-lg btn-block" href="#/nav/${o.id}">${icon('nav')}Navigatsiya: topshirish manziliga</a>
            <form id="deliver-form" class="stack-sm" novalidate>
              <label class="field"><span>Qabul qiluvchidan olingan 4 xonali kod</span>
                <input class="input code-input" name="code" inputmode="numeric" maxlength="4" required></label>
              <button class="btn btn-ok btn-lg btn-block" type="submit">Topshirdim</button></form></section>`;
      }
    } else if (o.status === 'NEW' && me && o.sender.id !== me.id) {
      if (!me.driver) {
        html += `<section class="panel stack-sm"><h2>${passenger ? "Bu yo'lovchini olib ketmoqchimisiz?" : 'Bu buyurtmani olmoqchimisiz?'}</h2>
          <p class="small muted">Buning uchun profilingizga mashinangizni qo'shing — 1 daqiqa.</p>
          <a class="btn btn-primary btn-block" href="#/profile?vehicle=1">Mashina qo'shish</a></section>`;
      } else if (o.myOffer) {
        html += `<section class="panel stack-sm"><h2>Sizning taklifingiz</h2>
          <p style="font-size:22px;font-weight:700" class="num">${esc(money(o.myOffer.price))}</p>
          ${o.myOffer.comment ? `<p class="small">${esc(o.myOffer.comment)}</p>` : ''}
          <p class="small muted">${passenger ? "Yo'lovchi" : 'Yuboruvchi'} tanlashini kuting — javob Telegramga keladi.</p>
          <div class="row"><button class="btn btn-sm" id="edit-offer">Narxni o'zgartirish</button>
          <button class="btn btn-danger btn-sm" id="withdraw">Taklifni qaytarish</button></div></section>`;
      } else {
        html += offerForm();
      }
    }
    if (o.status === 'DELIVERED' && (role === 'SENDER' || role === 'DRIVER')) {
      html += o.reviewedByMe
        ? `<section class="panel"><h2>Rahmat!</h2><p class="muted small" style="margin-top:6px">Siz bu buyurtmani baholadingiz.</p></section>`
        : `<section class="panel stack-sm"><h2>${role === 'SENDER' ? 'Haydovchini baholang' : passenger ? "Yo'lovchini baholang" : 'Yuboruvchini baholang'}</h2>
          <form id="review-form" class="stack-sm">
            <div class="star-pick" role="radiogroup" aria-label="Baho">${[1, 2, 3, 4, 5].map(n => `<button type="button" data-s="${n}" aria-label="${n} yulduz">★</button>`).join('')}</div>
            <textarea class="textarea" name="comment" maxlength="1000" placeholder="Fikringiz (ixtiyoriy)"></textarea>
            <button class="btn btn-primary btn-block" type="submit">Bahoni yuborish</button></form></section>`;
    }
    if (o.status === 'CANCELLED' && o.cancelReason) {
      html += `<div class="form-error">Bekor qilingan: ${esc(o.cancelReason)}</div>`;
    }
    if (role === 'STAFF' && o.status !== 'DELIVERED' && o.status !== 'CANCELLED') {
      html += `<button class="btn btn-danger btn-block" id="admin-cancel">Admin: buyurtmani bekor qilish</button>`;
    }
    box.innerHTML = html;
    bindActions();
  }

  /**
   * Shu yo'nalishda reys e'lon qilgan haydovchilar (e'lon egasiga): qo'ng'iroq qilib darhol kelishish mumkin.
   * Sanasi mos (o'sha kuni yoki ertasi, yoki «har kuni») reyslar birinchi.
   */
  async function loadRouteTrips() {
    const box = $('#route-trips', main);
    if (!box) return;
    try {
      const q = { fromRegion: o.from.regionId, toRegion: o.to.regionId };
      if (o.from.districtId) q.fromDistrict = o.from.districtId;
      const data = await get('/trips', q);
      const fits = (t) => {
        if (t.daily || !o.pickupDate) return true;
        const d = t.departAt.slice(0, 10);
        return d === o.pickupDate || d === addDays(o.pickupDate, 1);
      };
      const list = data.items.filter(t => t.driver && t.driver.id !== auth.user?.id)
        .sort((a, b) => Number(fits(b)) - Number(fits(a))).slice(0, 5);
      if (!list.length || !document.body.contains(box)) return;
      box.innerHTML = `<section class="panel stack-sm"><h3>Yo'nalishingizdagi haydovchilar</h3>
        <p class="small muted">Bular shu yo'nalishda reys e'lon qilgan — qo'ng'iroq qilib kelishishingiz mumkin. Sanasi va turi mos kelganlariga e'loningiz haqida xabar ham yuborildi.</p>
        ${list.map(t => `<div class="trip-mini">
          <a class="person" href="#/u/${t.driver.id}">${avatar(t.driver.avatarUrl, t.driver.fullName)}
            <div style="min-width:0"><div class="person-name">${esc(t.driver.fullName)} ${verifiedBadge(t.driver.verified, { short: true })}</div>
            <div class="person-sub">${esc(tripWhen(t))}${t.seats ? `, ${t.seats} ta bo'sh o'rin` : ''}${t.driver.vehicle ? ' · ' + esc(t.driver.vehicle.brand) : ''}</div></div></a>
          ${t.driver.phone ? `<a class="btn btn-sm btn-route" href="tel:${esc(t.driver.phone)}" aria-label="${esc(t.driver.fullName)} ga qo'ng'iroq">${icon('phone')}</a>` : ''}
        </div>`).join('')}
        <a class="small" href="#/trips?from=${o.from.regionId}&to=${o.to.regionId}">Barcha reyslar</a></section>`;
    } catch { /* ixtiyoriy blok */ }
  }

  /**
   * Haydovchi uchun: yukda — narxga rozi bo'lib olish yoki o'z narxini taklif qilish; pochta va yo'lovchida narx
   * ko'rsatilmagan bo'lsa ham «Olaman» (narx telefonda kelishiladi), o'z narxini taklif qilish — qo'shimcha.
   */
  function offerForm(existing) {
    const k = kindOf(o);
    const quick = !existing && (o.price || k !== 'CARGO');
    const takeText = o.price ? `${money(o.price)}ga roziman, olaman` : 'Olaman';
    const form = `<form id="offer-form" class="stack-sm" novalidate>
        <label class="field"><span>Narxingiz, so'm</span><input class="input" name="price" type="number" min="1000" step="1000" inputmode="numeric" value="${existing?.price || ''}" required></label>
        <label class="field"><span>Qachon ${k === 'PASSENGER' ? 'jo\'nay olasiz' : 'olib keta olasiz'}?</span><select class="select" name="pickupInHours">
          <option value="1">1 soat ichida</option><option value="3">3 soat ichida</option><option value="24" selected>Bugun-ertaga</option><option value="72">3 kun ichida</option></select></label>
        <label class="field"><span>Izoh</span><textarea class="textarea" name="comment" maxlength="500" placeholder="${k === 'PASSENGER' ? 'Masalan: Cobalt, konditsioner bor, ertalab 7 da chiqaman' : 'Masalan: yuklashga yordam beraman'}">${esc(existing?.comment || '')}</textarea></label>
        <button class="btn ${quick ? '' : 'btn-primary'} btn-block" type="submit">Taklif yuborish</button></form>`;
    return `<section class="panel stack-sm" id="offer-box"><h2>${existing ? 'Taklifni yangilash' : quick ? (k === 'PASSENGER' ? "Yo'lovchini olib ketasizmi?" : 'Olib ketasizmi?') : 'Narx taklif qiling'}</h2>
      ${quick ? `<button class="btn btn-primary btn-lg btn-block" id="take">${esc(takeText)}</button>
        <p class="small muted" style="text-align:center">Bossangiz — ikkalangizga bir-biringizning telefon raqami ko'rinadi${o.price ? '' : ', narxni telefonda kelishasiz'}.</p>
        <details class="more"><summary>O'z narxingizni taklif qilish</summary><div style="margin-top:12px">${form}</div></details>` : form}
      ${pr && freeLeft > 0 ? `<p class="tiny muted">🎁 Sizda yana <b>${freeLeft} ta</b> komissiyasiz buyurtma bor — bu buyurtma sizga berilsa, balansdan hech narsa yechilmaydi.</p>`
        : pr ? `<p class="tiny muted">Buyurtma sizga berilsa, balansingizdan komissiya yechiladi: narxning ${esc(num(pr.commissionPercent, 1))}%${pr.commissionMin ? `, kamida ${esc(money(pr.commissionMin))}` : ''}${o.price ? ` — shu narxda ${esc(money(commissionFor(pr, o.price)))}` : ''}.
        Yuboruvchi bekor qilsa, komissiya qaytariladi.</p>` : ''}</section>`;
  }

  function bindActions() {
    const on = (sel, fn) => { const el = $(sel, main); if (el) el.addEventListener('click', fn); };
    on('#cancel', async () => {
      const isDriver = o.viewerRole === 'DRIVER';
      const r = await confirmDialog(isDriver ? 'Voz kechasizmi?' : "E'lonni bekor qilasizmi?",
        isDriver ? "E'lon qayta ochiladi va boshqa haydovchilarga xabar boradi. Tez-tez voz kechish reytingingizga ta'sir qiladi."
            + (o.commission ? ` Yechilgan komissiya (${money(o.commission)}) qaytarilmaydi.` : '')
          : "Haydovchilar va takliflar bekor bo'ladi.",
        { danger: true, okText: isDriver ? 'Voz kechish' : 'Bekor qilish', withReason: true });
      if (!r) return;
      try { o = await post(`/orders/${id}/cancel`, { reason: r.reason }); toast('Bajarildi'); render(); } catch (e) { toast(e.message, 'err'); }
    });
    on('#admin-cancel', async () => {
      const r = await confirmDialog('Buyurtmani bekor qilish', 'Yuboruvchi va haydovchiga xabar boradi.', { danger: true, withReason: true });
      if (!r) return;
      try { await post(`/admin/orders/${id}/cancel`, { reason: r.reason }); toast('Bekor qilindi'); load(); } catch (e) { toast(e.message, 'err'); }
    });
    on('#pickup', async (e) => {
      try { o = await busy(e.currentTarget, () => post(`/orders/${id}/pickup`)); toast("Yo'lingiz bexatar bo'lsin!", 'ok'); render(); } catch (err) { toast(err.message, 'err'); }
    });
    on('#arrived', async (e) => {
      const btn = e.currentTarget;
      const r = await confirmDialog('Manzilga yetib keldingizmi?', "Safar yakunlanadi va yo'lovchiga baholash taklif qilinadi.", { okText: 'Ha, yetdik' });
      if (!r) return;
      try {
        o = await busy(btn, () => post(`/orders/${id}/deliver`, {}));
        toast('Ajoyib! Safar yakunlandi', 'ok');
        refreshMe();
        render();
      } catch (err) { toast(err.message, 'err'); }
    });
    on('#make-urgent', async (e) => {
      const btn = e.currentTarget;   // await dan keyin currentTarget null bo'lib qoladi
      const r = await confirmDialog("E'lonni shoshilinch qilasizmi?",
        urgentFee && !urgentFree ? `${money(urgentFee)} balansingizdan yechiladi. E'lon ro'yxatda eng yuqoriga chiqadi.`
          : (urgentFree ? "Birinchi shoshilinch e'loningiz bepul. " : '') + "E'lon ro'yxatda eng yuqoriga chiqadi va haydovchilarga qayta xabar boradi.",
        { okText: 'Shoshilinch qilish' });
      if (!r) return;
      try { o = await busy(btn, () => post(`/orders/${id}/urgent`)); toast("E'lon shoshilinch qilindi", 'ok'); render(); } catch (err) { toast(err.message, 'err'); }
    });
    on('#take', async () => {
      const passenger = kindOf(o) === 'PASSENGER';
      const fee = freeLeft > 0 ? 0 : (pr ? commissionFor(pr, o.price || 0) || pr.commissionMin || 0 : 0);
      const r = await confirmDialog(passenger ? "Yo'lovchini olasizmi?" : 'Buyurtmani olasizmi?',
        (o.price ? `${money(o.price)} narxda. ` : 'Narxni telefonda kelishasiz. ')
        + `${passenger ? "Yo'lovchiga" : 'Yuboruvchiga'} darhol xabar boradi va ikkalangizga bir-biringizning raqami ochiladi.`
        + (fee ? ` Balansingizdan ${money(fee)} komissiya yechiladi.` : (pr && freeLeft > 0 ? ' Komissiya olinmaydi (yangi haydovchi bonusi).' : '')),
        { okText: 'Olaman' });
      if (!r) return;
      try {
        o = await busy($('#take', main), () => post(`/orders/${id}/take`));
        toast(passenger ? "Yo'lovchi sizniki! Qo'ng'iroq qilib kelishib oling" : "Buyurtma sizniki! Yuboruvchiga qo'ng'iroq qiling", 'ok');
        render();
      } catch (err) { toast(err.message, 'err'); }
    });
    on('#withdraw', async () => {
      const r = await confirmDialog('Taklifni qaytarasizmi?', '', { danger: true, okText: 'Qaytarish' });
      if (!r) return;
      try { await post(`/orders/${id}/offers/${o.myOffer.id}/withdraw`); load(); } catch (e) { toast(e.message, 'err'); }
    });
    on('#edit-offer', () => {
      const sec = $('#edit-offer', main).closest('section');
      sec.outerHTML = offerForm(o.myOffer);
      bindOfferForm();
    });
    bindOfferForm();

    const df = $('#deliver-form', main);
    if (df) df.addEventListener('submit', async (e) => {
      e.preventDefault();
      clearError(df);
      try {
        o = await busy(df.querySelector('[type=submit]'), () => post(`/orders/${id}/deliver`, { code: df.code.value.trim() }));
        toast('Ajoyib! Buyurtma yakunlandi', 'ok');
        refreshMe();
        render();
      } catch (err) { showError(df, err); }
    });

    const rf = $('#review-form', main);
    if (rf) {
      let starsVal = 0;
      $$('.star-pick button', rf).forEach(b => b.addEventListener('click', () => {
        starsVal = Number(b.dataset.s);
        $$('.star-pick button', rf).forEach(x => x.classList.toggle('on', Number(x.dataset.s) <= starsVal));
      }));
      rf.addEventListener('submit', async (e) => {
        e.preventDefault();
        clearError(rf);
        if (!starsVal) return showError(rf, { message: 'Yulduzchani tanlang' });
        try {
          await busy(rf.querySelector('[type=submit]'), () => post(`/orders/${id}/review`, { stars: starsVal, comment: rf.comment.value.trim() || null }));
          toast('Bahoyingiz uchun rahmat!', 'ok');
          load();
        } catch (err) { showError(rf, err); }
      });
    }
    if ($('#offers', main)) loadOffers();
  }

  function bindOfferForm() {
    const of = $('#offer-form', main);
    if (!of) return;
    of.addEventListener('submit', async (e) => {
      e.preventDefault();
      clearError(of);
      const d = formData(of);
      try {
        await busy(of.querySelector('[type=submit]'), () => post(`/orders/${id}/offers`, {
          price: d.price, comment: d.comment, pickupInHours: Number(d.pickupInHours),
        }));
        toast('Taklifingiz yuborildi', 'ok');
        load();
      } catch (err) { showError(of, err); }
    });
  }

  async function loadOffers() {
    const box = $('#offers', main);
    try {
      const list = (await get(`/orders/${id}/offers`)).filter(f => f.status === 'PENDING');
      box.innerHTML = list.length ? list.map(f => `<div class="offer">
          <a class="person" href="#/u/${f.driver.id}">${avatar(f.driver.avatarUrl, f.driver.fullName)}
            <div style="min-width:0"><div class="person-name">${esc(f.driver.fullName)} ${verifiedBadge(f.driver.verified)}</div>
            <div class="person-sub">${stars(f.driver.rating, f.driver.ratingCount)}, ${f.driver.deliveriesCompleted} ta yetkazish</div></div></a>
          <div class="price">${esc(money(f.price))}</div>
          <div class="small muted" style="grid-column:1/-1">
            ${f.driver.vehicle ? `${esc(f.driver.vehicle.brand)}${f.driver.vehicle.model ? ' ' + esc(f.driver.vehicle.model) : ''}, ${esc(bodyLabel(f.driver.vehicle.bodyType))}` : ''}
            ${f.pickupInHours ? `<br>Olib ketadi: ${f.pickupInHours <= 3 ? f.pickupInHours + ' soat ichida' : f.pickupInHours <= 24 ? 'bugun-ertaga' : '3 kun ichida'}` : ''}
            ${f.comment ? `<br>«${esc(f.comment)}»` : ''}</div>
          <div class="offer-actions">
            <button class="btn btn-primary btn-sm" data-accept="${f.id}">Tanlash</button>
            <a class="btn btn-sm" href="tel:${esc(f.driver.phone)}">${icon('phone')}Qo'ng'iroq</a>
            <button class="btn btn-ghost btn-sm" data-reject="${f.id}">Rad etish</button>
          </div></div>`).join('')
        : `<p class="muted small" style="margin-top:10px">Hozircha taklif yo'q. Yo'nalishingizdagi haydovchilarga xabar yuborildi — kimdir olsa yoki narx taklif qilsa, darhol xabar keladi (Telegramga ham).</p>`;
      $$('[data-accept]', box).forEach(b => b.addEventListener('click', async () => {
        const f = list.find(x => x.id === Number(b.dataset.accept));
        const r = await confirmDialog('Haydovchini tanlaysizmi?', `${f.driver.fullName}, ${money(f.price)}. Qolgan takliflar rad etiladi.`, { okText: 'Tanlash' });
        if (!r) return;
        try { o = await post(`/orders/${id}/offers/${f.id}/accept`); toast('Haydovchi tanlandi', 'ok'); render(); } catch (e) { toast(e.message, 'err'); }
      }));
      $$('[data-reject]', box).forEach(b => b.addEventListener('click', async () => {
        try { await post(`/orders/${id}/offers/${b.dataset.reject}/reject`); loadOffers(); } catch (e) { toast(e.message, 'err'); }
      }));
    } catch (e) { box.innerHTML = `<p class="muted small">${esc(e.message)}</p>`; }
  }

  async function loadEvents() {
    try {
      const evs = await get(`/orders/${id}/events`);
      $('#timeline', main).innerHTML = evs.map(e => `<li><b>${esc(e.text)}</b><div class="small muted">${esc(dateTime(e.createdAt))}${e.actorName ? ', ' + esc(e.actorName) : ''}</div></li>`).join('');
    } catch { /* */ }
  }

  async function drawMap() {
    const el = $('#order-map', main);
    if (!el) return;
    const from = o.from.lat ? [o.from.lat, o.from.lng] : null;
    const to = o.to.lat ? [o.to.lat, o.to.lng] : null;
    const drv = o.driverLat != null ? [o.driverLat, o.driverLng] : null;
    // Aniq nuqtalar bo'lmasa (faol buyurtmada haydovchini kuzatish uchun) — viloyat markazi
    let center = from || to || drv;
    if (!center) {
      const r = (await regions().catch(() => [])).find(x => x.id === o.from.regionId);
      if (r && r.lat != null) center = [r.lat, r.lng];
    }
    if (!center) {
      el.innerHTML = `<div class="map-empty">Aniq manzillar xaritada belgilanmagan</div>`;
      return;
    }
    try {
      mapCtx = await createMap(el, { center, zoom: from || to || drv ? 10 : 8 });
      routeDrawn = false;
      const { ymaps, map } = mapCtx;
      if (from) map.geoObjects.add(pin(ymaps, from, 'from', { label: 'Olish' }));
      if (to) map.geoObjects.add(pin(ymaps, to, 'to', { label: 'Topshirish' }));
      if (from && to) { await drawRoute(ymaps, map, [from, to]); routeDrawn = true; }
      updateDriverMark();
    } catch { /* */ }
  }

  function updateDriverMark() {
    if (!mapCtx || o.driverLat == null) return;
    const c = [o.driverLat, o.driverLng];
    if (!driverMark) {
      driverMark = pin(mapCtx.ymaps, c, 'driver', { hint: 'Haydovchi' });
      mapCtx.map.geoObjects.add(driverMark);
    } else driverMark.geometry.setCoordinates(c);
    void routeDrawn;
  }

  async function initChat() {
    const list = $('#chat-list', main);
    const form = $('#chat-form', main);
    lastMsgId = 0;
    list.innerHTML = '';
    const pull = async () => {
      try {
        const msgs = await get(`/orders/${id}/messages`, { after: lastMsgId });
        if (!msgs.length) {
          if (!list.children.length) {
            list.innerHTML = `<p class="muted small" style="margin:auto;text-align:center" id="chat-empty">${form
              ? "Yuk bo'yicha savollaringizni shu yerda yozing. Xabar Telegramga ham boradi."
              : "Xabarlar yo'q."}</p>`;
          }
          return;
        }
        $('#chat-empty', list)?.remove();
        for (const m of msgs) {
          lastMsgId = Math.max(lastMsgId, m.id);
          const mine = m.authorId === auth.user.id;
          list.insertAdjacentHTML('beforeend', `<div class="msg ${mine ? 'mine' : ''}">${esc(m.text)}<time>${esc(dateTime(m.createdAt))}</time></div>`);
        }
        list.scrollTop = list.scrollHeight;
      } catch { /* */ }
    };
    form?.addEventListener('submit', async (e) => {
      e.preventDefault();
      const text = form.text.value.trim();
      if (!text) return;
      form.text.value = '';
      try { await post(`/orders/${id}/messages`, { text }); pull(); } catch (err) {
        toast(err.message, 'err');
        form.text.value = text;
        if (err.status === 403) load();   // masalan, ikkinchi tomon bloklagan — sahifa yopiq yozishmani ko'rsatadi
      }
    });
    await pull();
    clearInterval(chatTimer);
    chatTimer = setInterval(pull, 6000);
  }

  await load();
  // Faol buyurtmada haydovchi joylashuvini va holatni yangilab turamiz
  timer = setInterval(async () => {
    if (!o || o.status === 'DELIVERED' || o.status === 'CANCELLED') return;
    try {
      const fresh = await get('/orders/' + id);
      const changed = needsRender || fresh.status !== o.status || fresh.offersCount !== o.offersCount
        || fresh.driver?.id !== o.driver?.id || fresh.block !== o.block;
      o = fresh;
      // Foydalanuvchi hozir yozayotgan bo'lsa — sahifani qayta chizmaymiz, lekin o'zgarishni unutmaymiz (keyingi safar)
      if (changed && !document.activeElement?.closest('form')) { needsRender = false; driverMark = null; render(); }
      else { if (changed) needsRender = true; updateDriverMark(); }
    } catch { /* */ }
  }, 10000);
  return () => { clearInterval(timer); clearInterval(chatTimer); };
}

/**
 * Shikoyat yoki murojaat. opts.onBlocked berilsa — «Bloklash ham» belgisi chiqadi (shikoyat bilan birga bloklash).
 */
export function reportDialog(targetUserId, orderId, { name, onBlocked } = {}) {
  const canBlock = !!onBlocked && !!targetUserId;
  modal('Shikoyat yoki murojaat', `<form class="stack" novalidate>
      <p class="small muted">Murojaatingizni adminlar ko'rib chiqadi, javobi bildirishnomalarda (Telegram ulangan bo'lsa — Telegramda ham) keladi.</p>
      <label class="field"><span>Nima bo'ldi?</span><textarea class="textarea" name="text" minlength="5" maxlength="1000" required></textarea></label>
      ${canBlock ? `<label class="check"><input type="checkbox" name="alsoBlock">
        <span>${name ? esc(name) + 'ni' : 'Bu foydalanuvchini'} bloklash ham — bir-biringizga yoza olmaysiz</span></label>` : ''}
      <button class="btn btn-primary" type="submit">Yuborish</button></form>`, {
    onMount: ({ el, close }) => {
      const form = $('form', el);
      form.addEventListener('submit', async (e) => {
        e.preventDefault();
        const alsoBlock = canBlock && form.alsoBlock.checked;
        try {
          await busy(form.querySelector('[type=submit]'), async () => {
            await post('/reports', { targetUserId, orderId, text: form.text.value.trim() });
            if (alsoBlock) await post(`/users/${targetUserId}/block`);
          });
          toast(alsoBlock ? 'Murojaat yuborildi, foydalanuvchi bloklandi' : 'Murojaat yuborildi', 'ok');
          close();
          if (alsoBlock) onBlocked();
        } catch (err) { showError(form, err); }
      });
    },
  });
}

/** Foydalanuvchini bloklash (tasdiqlash oynasi bilan). onDone — sahifani yangilash. */
export async function blockUser(userId, name, onDone) {
  const ok = await confirmDialog(`${name || 'Foydalanuvchi'} bloklansinmi?`,
    "Bir-biringizga yoza olmaysiz, u e'lonlaringizga taklif bera olmaydi, bozor, reyslar va haydovchilar ro'yxatida bir-biringizni ko'rmaysiz. "
    + "Boshlangan buyurtma davom etadi, faqat yozishma yopiladi. Profil → Bloklanganlar bo'limida istalgan vaqtda blokdan chiqarishingiz mumkin.",
    { okText: 'Bloklash', danger: true });
  if (!ok) return false;
  try {
    await post(`/users/${userId}/block`);
    toast('Foydalanuvchi bloklandi', 'ok');
    if (onDone) onDone();
    return true;
  } catch (err) { toast(err.message, 'err'); return false; }
}

export async function unblockUser(userId, onDone) {
  try {
    await del(`/users/${userId}/block`);
    toast('Blokdan chiqarildi', 'ok');
    if (onDone) onDone();
    return true;
  } catch (err) { toast(err.message, 'err'); return false; }
}

// =====================================================================
// Mening buyurtmalarim
// =====================================================================
export async function myOrdersPage(main, _p, query) {
  const me = auth.user;
  let role = query.get('role') || (me.driver ? 'driver' : 'sender');
  let group = 'active', page = 0, items = [];
  main.innerHTML = `<div class="page page-narrow">
    <div class="page-head"><h1>Mening buyurtmalarim</h1><a class="btn btn-primary" href="#/new">${icon('plus')}Yangi e'lon</a></div>
    <div class="row-between" style="margin-bottom:14px">
      <div class="seg" id="roles">
        <button data-role="sender">E'lonlarim</button>
        <button data-role="driver">Olganlarim</button>
        <button data-role="offers">Takliflarim</button>
      </div>
      <div class="seg" id="groups"><button data-group="active" class="on">Faol</button><button data-group="done">Yakunlangan</button><button data-group="all">Hammasi</button></div>
    </div>
    <div id="list">${skeletons()}</div>
    <div class="row" style="justify-content:center;margin-top:16px"><button class="btn hidden" id="more">Yana ko'rsatish</button></div>
  </div>`;
  const sync = () => {
    $$('#roles button', main).forEach(b => b.classList.toggle('on', b.dataset.role === role));
    $$('#groups button', main).forEach(b => b.classList.toggle('on', b.dataset.group === group));
    $('#groups', main).classList.toggle('hidden', role === 'offers');
  };
  $$('#roles button', main).forEach(b => b.addEventListener('click', () => { role = b.dataset.role; sync(); reset(); }));
  $$('#groups button', main).forEach(b => b.addEventListener('click', () => { group = b.dataset.group; sync(); reset(); }));
  $('#more', main).addEventListener('click', () => { page++; load(); });
  function reset() { page = 0; items = []; $('#list', main).innerHTML = skeletons(); load(); }
  async function load() {
    try {
      const data = await get('/orders/my', { role, group, page });
      items = items.concat(data.items);
      const empty = {
        sender: ["Siz hali e'lon bermagansiz", "Yuk, pochta jo'natish yoki yo'lovchi sifatida borish uchun e'lon bering.", `<a class="btn btn-primary" href="#/new">E'lon berish</a>`],
        driver: ["Hozircha olgan buyurtmangiz yo'q", "E'lonlardan mosini toping yoki reys e'lon qiling — mos e'lonlar haqida xabar keladi.", `<a class="btn btn-primary" href="#/">E'lonlarni ko'rish</a>`],
        offers: ["Javob kutayotgan taklifingiz yo'q", "E'lonlarga narx taklif qilsangiz, shu yerda ko'rinadi.", `<a class="btn" href="#/">E'lonlarni ko'rish</a>`],
      }[role];
      $('#list', main).innerHTML = items.length ? `<div class="orders">${items.map(orderRow).join('')}</div>` : emptyState(...empty);
      $('#more', main).classList.toggle('hidden', data.page + 1 >= data.totalPages);
    } catch (e) { $('#list', main).innerHTML = emptyState("Yuklab bo'lmadi", e.message); }
  }
  sync();
  load();
}
