// Buyurtmalar: bozor, yaratish, batafsil ko'rinish, mening buyurtmalarim
import { auth, get, post, upload, regions, config } from '../api.js';
import {
  $, $$, esc, icon, money, km, duration, date, dateTime, ago, phoneFmt, avatar, stars, toast, modal, confirmDialog,
  formData, showError, clearError, busy, bindRegionDistrict, regionOptions, options, routeStrip, statusPill,
  emptyState, skeletons, CARGO, BODY, SCOPE, PAYMENT, PAYER, num, verifiedBadge,
} from '../ui.js';
import { createMap, pin, drawRoute, reverseGeocode, myPosition, UZ_CENTER } from '../maps.js';
import { go, refreshMe } from '../app.js';
import { digitalSalesBlocked } from '../platform.js';

// =====================================================================
// Bozor (bosh sahifa)
// =====================================================================
export async function marketPage(main, _p, query) {
  const f = {
    fromRegion: query.get('from') || '', toRegion: query.get('to') || '', cargoType: '', scope: '', bodyType: '', q: '',
  };
  let page = 0, view = 'list', mapCtx = null;
  const stats = await get('/stats/public').catch(() => null);

  main.innerHTML = `<div class="page">
    ${auth.loggedIn ? '' : intro(stats)}
    <div class="page-head">
      <div><h1>Yuk buyurtmalari</h1><p class="muted">Haydovchi kutayotgan ochiq buyurtmalar. Marshrutni tanlang.</p></div>
      <div class="seg" role="tablist">
        <button class="on" data-view="list">${'Ro\'yxat'}</button><button data-view="map">Xarita</button>
      </div>
    </div>
    <form class="route-search" id="rs">
      <select class="select" name="fromRegion" aria-label="Qayerdan">${await regionOptions(f.fromRegion, 'Qayerdan: hamma viloyatlar')}</select>
      <button type="button" class="swap-btn" id="swap" aria-label="Yo'nalishni almashtirish">${icon('swap')}</button>
      <select class="select" name="toRegion" aria-label="Qayerga">${await regionOptions(f.toRegion, 'Qayerga: hamma viloyatlar')}</select>
      <button class="btn btn-primary btn-lg" type="submit">Qidirish</button>
    </form>
    <div class="filters">
      <select class="select" name="cargoType" aria-label="Yuk turi">${options(CARGO, '', 'Har qanday yuk')}</select>
      <select class="select" name="scope" aria-label="Yo'nalish turi">${options(SCOPE, '', 'Har qanday masofa')}</select>
      <select class="select" name="bodyType" aria-label="Mashina turi">${options(BODY, auth.user?.vehicle?.bodyType || '', 'Har qanday mashina')}</select>
      <input class="input" name="q" type="search" placeholder="Qidiruv: mebel, un, sement…" aria-label="Qidiruv">
    </div>
    <div id="results" style="margin-top:16px">${skeletons()}</div>
    <div id="map-view" class="hidden" style="margin-top:16px"><div class="map map-tall" id="market-map"></div></div>
    <div class="row" style="justify-content:center;margin-top:16px"><button class="btn hidden" id="more">Yana ko'rsatish</button></div>
  </div>`;

  const rs = $('#rs', main);
  f.bodyType = $('[name=bodyType]', main).value;
  $('#swap', main).addEventListener('click', () => {
    const a = rs.fromRegion.value; rs.fromRegion.value = rs.toRegion.value; rs.toRegion.value = a;
    reload();
  });
  rs.addEventListener('submit', (e) => { e.preventDefault(); reload(); });
  $$('.filters [name]', main).forEach(el => el.addEventListener(el.tagName === 'INPUT' ? 'change' : 'change', reload));
  $('#more', main).addEventListener('click', () => load(true));
  $$('[data-view]', main).forEach(b => b.addEventListener('click', () => {
    view = b.dataset.view;
    $$('[data-view]', main).forEach(x => x.classList.toggle('on', x === b));
    $('#results', main).classList.toggle('hidden', view !== 'list');
    $('#map-view', main).classList.toggle('hidden', view !== 'map');
    if (view === 'map') reload();
  }));

  let items = [];
  function reload() {
    f.fromRegion = rs.fromRegion.value; f.toRegion = rs.toRegion.value;
    for (const el of $$('.filters [name]', main)) f[el.name] = el.value;
    page = 0; items = [];
    load(false);
  }
  async function load(more) {
    if (more) page++;
    const res = $('#results', main);
    if (!more) res.innerHTML = skeletons();
    try {
      const data = await get('/orders', { ...f, page });
      items = items.concat(data.items);
      res.innerHTML = items.length
        ? groupedByDay(items)
        : emptyState("Bu yo'nalishda hozircha buyurtma yo'q", "Filtrlarni o'zgartiring yoki birinchi bo'lib buyurtma bering.",
          `<a class="btn btn-primary" href="#/new">Buyurtma berish</a>`);
      $('#more', main).classList.toggle('hidden', data.page + 1 >= data.totalPages);
      if (view === 'map') showOnMap();
    } catch (e) {
      res.innerHTML = emptyState("Buyurtmalarni yuklab bo'lmadi", e.message);
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
          balloonContentHeader: esc(o.title),
          balloonContentBody: `${esc(o.from.regionName)} → ${esc(o.to.regionName)}<br><b>${esc(money(o.price))}</b>`,
          balloonContentFooter: `<a href="#/orders/${o.id}">Batafsil ko'rish</a>`,
          hintContent: esc(o.title),
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
      <h1>Yukingizni viloyatdan viloyatga ishonchli yetkazamiz</h1>
      <p>Buyurtma bering, haydovchilar narx taklif qiladi. Reyting bo'yicha tanlang va yukingizni xaritada jonli kuzating.</p>
      <div class="row"><a class="btn btn-primary btn-lg" href="#/register">Ro'yxatdan o'tish</a><a class="btn btn-lg" href="#/login">Kirish</a></div>
      ${stats ? `<div class="intro-facts">
        <div><b class="num">${num(stats.openOrders)}</b>ochiq buyurtma</div>
        <div><b class="num">${num(stats.drivers)}</b>haydovchi</div>
        <div><b class="num">${num(stats.delivered)}</b>yetkazilgan yuk</div></div>` : ''}
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

// ---- Olib ketish sanasi bo'yicha guruhlar: Shoshilinch → Bugun → Ertaga → Indinga → sanalar → muddati o'tgan → sanasiz ----
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
        <div class="orders">${list.map(orderRow).join('')}</div></section>`)
    .join('');
}

export function orderRow(o) {
  const late = o.status === 'NEW' && o.pickupDate && o.pickupDate < localISO();
  const meta = [
    o.distanceKm ? `<span class="only-mobile"><b>${esc(km(o.distanceKm))}</b></span>` : '',
    `<span>${esc(CARGO[o.cargoType] || o.cargoType)}</span>`,
    o.weightKg ? `<span><b>${num(o.weightKg, 1)}</b> kg</span>` : '',
    o.volumeM3 ? `<span><b>${num(o.volumeM3, 1)}</b> m³</span>` : '',
    o.pickupDate ? `<span>Olib ketish: <b>${esc(date(o.pickupDate + 'T00:00:00'))}</b>${late ? ' <em class="late">kechikkan</em>' : ''}</span>` : '',
    o.requiredBodyType ? `<span>${esc(BODY[o.requiredBodyType])}</span>` : '',
    o.status === 'NEW' && o.offersCount ? `<span><b>${o.offersCount}</b> ta taklif</span>` : '',
    `<span>${esc(ago(o.createdAt))}</span>`,
  ].filter(Boolean).join('');
  return `<a class="order-row" href="#/orders/${o.id}">
    <div class="order-top">
      <div class="order-title">${o.urgent ? '<span class="urgent-flag">Shoshilinch</span>' : ''}${esc(o.title)}</div>
      ${o.status === 'NEW'
        ? `<div class="price ${o.price ? '' : 'nego'}">${esc(money(o.price))}</div>`
        : statusPill(o.status)}
    </div>
    ${routeStrip(o)}
    <div class="meta">${meta}</div>
  </a>`;
}

// =====================================================================
// Yangi buyurtma
// =====================================================================
/**
 * Botga yozilgan e'londan to'ldirilgan forma: "#/new?bot=1&fr=1&fd=5&tr=12&w=5000&p=3000000&d=2026-10-23&b=TENT&c=FURNITURE&t=..&x=.."
 * Noto'g'ri yoki eskirgan qiymatlar jim e'tiborsiz qoldiriladi.
 */
function prefillFrom(query, today) {
  const q = query instanceof URLSearchParams ? query : new URLSearchParams();
  const id = (k) => (/^\d{1,18}$/.test(q.get(k) || '') ? Number(q.get(k)) : null);
  const positive = (k) => { const v = Number(q.get(k)); return Number.isFinite(v) && v > 0 ? v : null; };
  const d = q.get('d') || '';
  const known = (map, k) => Object.prototype.hasOwnProperty.call(map, k);   // eski WebView'larda Object.hasOwn yo'q
  return {
    fromBot: q.get('bot') === '1',
    fr: id('fr'), fd: id('fd'), tr: id('tr'), td: id('td'),
    weight: positive('w'), price: positive('p'), negotiable: q.get('n') === '1',
    date: /^\d{4}-\d{2}-\d{2}$/.test(d) && d >= today ? d : today,
    body: known(BODY, q.get('b') || '') ? q.get('b') : '',
    cargo: known(CARGO, q.get('c') || '') ? q.get('c') : 'GENERAL',
    title: (q.get('t') || '').slice(0, 120),
    description: (q.get('x') || '').slice(0, 1500),
  };
}

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
  main.innerHTML = `<div class="page">
    <div class="page-head"><div><h1>Yangi buyurtma</h1><p class="muted">Yukingiz haqida yozing — mos haydovchilarga Telegram orqali darhol xabar boradi.</p></div></div>
    ${pre.fromBot ? `<div class="prefill-note">${icon('check')}<div><b>Botga yozgan e'loningizdan to'ldirildi.</b> Tekshirib chiqing, qabul qiluvchini yozing va «E'lon qilish» ni bosing.</div></div>` : ''}
    <form id="order-form" class="grid-2" novalidate>
      <div class="stack">
        <section class="panel stack">
          <h2>Yuk</h2>
          <div class="form-grid">
            <label class="field full"><span>Nima yuboryapsiz?</span><input class="input" name="title" maxlength="120" placeholder="Masalan: 20 qop sement, divan va 2 ta shkaf" value="${esc(pre.title)}" required></label>
            <label class="field"><span>Yuk turi</span><select class="select" name="cargoType" required>${options(CARGO, pre.cargo)}</select></label>
            <label class="field"><span>Kerakli mashina</span><select class="select" name="requiredBodyType">${options(BODY, pre.body, 'Farqi yo\'q')}</select></label>
            <label class="field"><span>Og'irligi, kg</span><input class="input" name="weightKg" type="number" min="0.1" step="0.1" inputmode="decimal" placeholder="500" value="${pre.weight ?? ''}"></label>
            <label class="field"><span>Hajmi, m³</span><input class="input" name="volumeM3" type="number" min="0" step="0.1" inputmode="decimal" placeholder="ixtiyoriy"></label>
            <label class="field full"><span>Qo'shimcha ma'lumot</span><textarea class="textarea" name="description" maxlength="1500" placeholder="Qavat, lift bormi, yuklashga yordam kerakmi, qadoqlanganmi…">${esc(pre.description)}</textarea></label>
            <label class="field full"><span>Yuk rasmi (ixtiyoriy)</span><input class="input" name="photo" type="file" accept="image/jpeg,image/png,image/webp"></label>
          </div>
        </section>

        <section class="panel stack">
          <h2>Marshrut</h2>
          <div class="form-grid">
            <label class="field"><span>Qayerdan: viloyat</span><select class="select" name="fromRegionId" required></select></label>
            <label class="field"><span>Tuman / shahar</span><select class="select" name="fromDistrictId"></select></label>
            <label class="field full"><span>Yukni olish manzili</span><input class="input" name="fromAddress" maxlength="250" placeholder="Xaritadan belgilang yoki yozing"></label>
            <label class="field"><span>Qayerga: viloyat</span><select class="select" name="toRegionId" required></select></label>
            <label class="field"><span>Tuman / shahar</span><select class="select" name="toDistrictId"></select></label>
            <label class="field full"><span>Topshirish manzili</span><input class="input" name="toAddress" maxlength="250" placeholder="Xaritadan belgilang yoki yozing"></label>
          </div>
          <div class="map-tools">
            <div class="seg" id="pick-mode"><button type="button" class="on" data-mode="from">Olish nuqtasi</button><button type="button" data-mode="to">Topshirish nuqtasi</button></div>
            <button type="button" class="btn btn-sm" id="my-loc">${icon('locate')}Mening joylashuvim</button>
          </div>
          <div class="map" id="pick-map"></div>
          <p class="small muted" id="route-info">Xaritaga bosib olish va topshirish nuqtalarini belgilang — masofa avtomatik hisoblanadi.</p>
        </section>
      </div>

      <div class="stack">
        <section class="panel stack">
          <h2>Narx va to'lov</h2>
          <div class="panel-sunk hidden" id="estimate"></div>
          <label class="field"><span>Taklif qilayotgan narxingiz, so'm</span>
            <input class="input" name="price" type="number" min="1000" step="1000" inputmode="numeric" placeholder="300000" value="${pre.negotiable ? '' : (pre.price ?? '')}" ${pre.negotiable ? 'disabled' : ''}></label>
          <label class="check"><input type="checkbox" name="negotiable" ${pre.negotiable ? 'checked' : ''}> Narx kelishiladi — haydovchilar o'zi taklif qilsin</label>
          <div class="form-grid">
            <label class="field"><span>To'lov usuli</span><select class="select" name="paymentMethod">${options(PAYMENT, 'CASH')}</select></label>
            <label class="field"><span>Kim to'laydi</span><select class="select" name="payer">${options(PAYER, 'SENDER')}</select></label>
          </div>
          <label class="field"><span>Yukni olish sanasi</span><input class="input" name="pickupDate" type="date" min="${today}" value="${pre.date}"></label>
          ${urgentHidden ? '' : `<label class="check"><input type="checkbox" name="urgent"> Shoshilinch — ro'yxatda doim eng yuqorida turadi${urgentFee
            ? (urgentFree ? ` <b class="fee fee-free">Bepul</b> <s class="tiny muted">${esc(money(urgentFee))}</s>` : ` <b class="fee">${esc(money(urgentFee))}</b>`) : ''}</label>
          ${urgentFee ? `<p class="tiny muted">${urgentFree ? "Birinchi shoshilinch e'loningiz bepul — balansdan hech narsa yechilmaydi."
            : "Shoshilinch narxi e'lon joylanganda balansingizdan yechiladi."}</p>` : ''}`}
        </section>
        <section class="panel stack">
          <h2>Qabul qiluvchi</h2>
          <label class="field"><span>Ism-familiyasi</span><input class="input" name="receiverName" maxlength="120" required></label>
          <label class="field"><span>Telefon raqami</span><input class="input" name="receiverPhone" type="tel" inputmode="tel" placeholder="+998 90 123 45 67" required>
            <small>Yuk yo'lga chiqqach, topshirish kodi sizga ko'rinadi. Uni qabul qiluvchiga aytasiz — haydovchi shu kod bilan yukni topshiradi.</small></label>
          <label class="check"><input type="checkbox" id="me-receiver"> Qabul qiluvchi — o'zim</label>
        </section>
        <button class="btn btn-primary btn-lg btn-block" type="submit">Buyurtmani e'lon qilish</button>
        ${cfg.channelPosting ? `<p class="tiny muted" style="text-align:center">📣 E'loningiz YukGo Telegram kanallarida ham chiqadi — ismingiz va telefoningiz u yerda ko'rsatilmaydi.</p>` : ''}
      </div>
    </form>
  </div>`;

  const form = $('#order-form', main);
  const me = auth.user;
  await Promise.all([
    bindRegionDistrict(form.fromRegionId, form.fromDistrictId, pre.fr
      ? { regionId: pre.fr, districtId: pre.fd, allowEmptyDistrict: true }
      : { regionId: me?.region?.id, districtId: me?.district?.id, allowEmptyDistrict: true }),
    bindRegionDistrict(form.toRegionId, form.toDistrictId, { regionId: pre.tr, districtId: pre.td, allowEmptyDistrict: true }),
  ]);
  $('#me-receiver', main).addEventListener('change', (e) => {
    form.receiverName.value = e.target.checked ? me.fullName : '';
    form.receiverPhone.value = e.target.checked ? me.phone : '';
  });
  form.negotiable.addEventListener('change', () => { form.price.disabled = form.negotiable.checked; });

  // ---- Xarita bilan nuqta tanlash ----
  const pts = { from: null, to: null };
  let route = { distanceKm: null, durationMin: null };
  let mode = 'from', ctx = null, marks = {}, routeObj = null;
  $$('#pick-mode button', main).forEach(b => b.addEventListener('click', () => {
    mode = b.dataset.mode;
    $$('#pick-mode button', main).forEach(x => x.classList.toggle('on', x === b));
  }));
  try {
    ctx = await createMap($('#pick-map', main), { center: me?.region ? [me.region.lat, me.region.lng] : UZ_CENTER, zoom: me?.region ? 10 : 6 });
    ctx.map.events.add('click', (e) => setPoint(mode, e.get('coords'), true));
  } catch { /* xabar xaritada */ }

  const centerOn = async (sel) => {
    if (!ctx) return;
    const r = (await regions()).find(x => x.id === Number(sel.value));
    if (r) ctx.map.setCenter([r.lat, r.lng], 9, { duration: 300 });
  };
  form.fromRegionId.addEventListener('change', () => { mode = 'from'; centerOn(form.fromRegionId); });
  form.toRegionId.addEventListener('change', () => { mode = 'to'; $$('#pick-mode button', main)[1].click(); centerOn(form.toRegionId); });

  $('#my-loc', main).addEventListener('click', async () => {
    try {
      const c = await myPosition();
      setPoint(mode, c, true);
      ctx?.map.setCenter(c, 14);
    } catch (e) { toast(e.message, 'err'); }
  });

  async function setPoint(kind, coords, fill) {
    if (!ctx) return;
    pts[kind] = coords;
    if (marks[kind]) ctx.map.geoObjects.remove(marks[kind]);
    marks[kind] = pin(ctx.ymaps, coords, kind, { label: kind === 'from' ? 'Olish' : 'Topshirish', draggable: true });
    marks[kind].events.add('dragend', () => setPoint(kind, marks[kind].geometry.getCoordinates(), true));
    ctx.map.geoObjects.add(marks[kind]);
    if (fill) {
      const addr = await reverseGeocode(ctx.ymaps, coords);
      if (addr) form[kind === 'from' ? 'fromAddress' : 'toAddress'].value = addr;
    }
    if (kind === 'from' && !pts.to) $$('#pick-mode button', main)[1].click();
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
    if (!route.distanceKm) return;
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
      $('#use-est', main).addEventListener('click', () => { form.negotiable.checked = false; form.price.disabled = false; form.price.value = e.suggested; });
    } catch { /* */ }
  }
  form.weightKg.addEventListener('change', estimate);

  form.addEventListener('submit', async (e) => {
    e.preventDefault();
    clearError(form);
    const d = formData(form);
    if (!d.negotiable && !d.price) return showError(form, { message: "Narxni kiriting yoki \"Narx kelishiladi\" ni belgilang" });
    const body = {
      title: d.title, cargoType: d.cargoType, weightKg: d.weightKg, volumeM3: d.volumeM3, description: d.description,
      requiredBodyType: d.requiredBodyType || null,
      from: { regionId: Number(d.fromRegionId) || null, districtId: Number(d.fromDistrictId) || null, address: d.fromAddress, lat: pts.from?.[0], lng: pts.from?.[1] },
      to: { regionId: Number(d.toRegionId) || null, districtId: Number(d.toDistrictId) || null, address: d.toAddress, lat: pts.to?.[0], lng: pts.to?.[1] },
      distanceKm: route.distanceKm, durationMin: route.durationMin,
      pickupDate: d.pickupDate, price: d.negotiable ? null : d.price,
      paymentMethod: d.paymentMethod, payer: d.payer, urgent: !!d.urgent,
      receiverName: d.receiverName, receiverPhone: d.receiverPhone,
    };
    try {
      const o = await busy(form.querySelector('[type=submit]'), async () => {
        const created = await post('/orders', body);
        const file = form.photo.files[0];
        if (file) await upload(`/orders/${created.id}/photo`, file).catch(err => toast('Rasm yuklanmadi: ' + err.message, 'err'));
        return created;
      });
      toast("Buyurtma e'lon qilindi! Takliflar kelishini kuting", 'ok');
      go('#/orders/' + o.id);
    } catch (err) { showError(form, err); }
  });
  void cfg;
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
  let o, timer = null, chatTimer = null, mapCtx = null, driverMark = null, lastMsgId = 0, routeDrawn = false;
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

  function render() {
    const role = o.viewerRole;
    const isSender = role === 'SENDER', isDriver = role === 'DRIVER';
    const participant = isSender || isDriver;
    main.innerHTML = `<div class="page">
      <div class="page-head">
        <div>
          <div class="row" style="margin-bottom:8px">${statusPill(o.status)}<span class="muted small">#${o.id}, ${esc(ago(o.createdAt))}</span>
            ${o.urgent ? '<span class="urgent-flag">Shoshilinch</span>' : ''}</div>
          <h1>${esc(o.title)}</h1>
        </div>
        <div class="price ${o.finalPrice || o.price ? '' : 'nego'}" style="font-size:24px">${esc(money(o.finalPrice ?? o.price))}</div>
      </div>
      <div class="grid-2">
        <div class="stack">
          <section class="panel stack">
            ${routeStrip(o, { large: true })}
            <div class="map" id="order-map"></div>
            ${participant && o.from.address ? `<dl class="kv small">
              <dt>Olish manzili</dt><dd>${esc(o.from.address)}</dd>
              <dt>Topshirish manzili</dt><dd>${esc(o.to.address || '—')}</dd></dl>` : ''}
          </section>
          <section class="panel">
            <h2>Yuk haqida</h2>
            <dl class="kv">
              <dt>Turi</dt><dd>${esc(CARGO[o.cargoType])}</dd>
              ${o.weightKg ? `<dt>Og'irligi</dt><dd class="num">${num(o.weightKg, 1)} kg</dd>` : ''}
              ${o.volumeM3 ? `<dt>Hajmi</dt><dd class="num">${num(o.volumeM3, 1)} m³</dd>` : ''}
              ${o.requiredBodyType ? `<dt>Mashina</dt><dd>${esc(BODY[o.requiredBodyType])}</dd>` : ''}
              <dt>Yo'nalish</dt><dd>${esc(SCOPE[o.scope])}${o.distanceKm ? ', ' + esc(km(o.distanceKm)) : ''}</dd>
              ${o.pickupDate ? `<dt>Olib ketish</dt><dd>${esc(date(o.pickupDate))}</dd>` : ''}
              <dt>To'lov</dt><dd>${esc(PAYMENT[o.paymentMethod])}, ${esc(PAYER[o.payer]).toLowerCase()}</dd>
              ${participant ? `<dt>Qabul qiluvchi</dt><dd>${esc(o.receiverName)}, <a href="tel:${esc(o.receiverPhone)}">${esc(phoneFmt(o.receiverPhone))}</a></dd>` : ''}
            </dl>
            ${o.description ? `<p style="margin-top:14px;white-space:pre-line">${esc(o.description)}</p>` : ''}
            ${o.photoUrl ? `<img src="${esc(o.photoUrl)}" alt="Yuk rasmi" style="margin-top:14px;border-radius:12px;max-height:320px;object-fit:cover">` : ''}
          </section>
          ${participant && o.driver ? `<section class="panel"><h2>Yozishma</h2>
            <div class="chat" style="margin-top:12px"><div class="chat-list" id="chat-list"></div>
              <form class="chat-form" id="chat-form"><input class="input" name="text" maxlength="1000" placeholder="Xabar yozing…" autocomplete="off" aria-label="Xabar">
                <button class="btn btn-route" type="submit">Yuborish</button></form></div></section>` : ''}
          ${participant || auth.isStaff ? `<section class="panel"><h2>Tarix</h2><ul class="timeline" id="timeline" style="margin-top:14px"></ul></section>` : ''}
        </div>
        <div class="stack">
          <div id="actions" class="stack"></div>
          <section class="panel stack-sm">
            <h3>Yuboruvchi</h3>${personCard(o.sender)}
          </section>
          ${o.driver ? `<section class="panel stack-sm"><h3>Haydovchi</h3>${personCard(o.driver, true)}</section>` : ''}
          ${!participant && auth.user ? `<button class="btn btn-ghost btn-sm" id="report">Shikoyat qilish</button>` : ''}
        </div>
      </div>
    </div>`;
    renderActions();
    drawMap();
    if ($('#timeline', main)) loadEvents();
    if ($('#chat-form', main)) initChat();
    $('#report', main)?.addEventListener('click', () => reportDialog(o.sender.id, o.id));
  }

  function personCard(u, withVehicle) {
    // Server raqamni faqat buyurtma tomonlariga yuboradi; boshqalarga sababini tushuntiramiz
    const lockText = o.status === 'NEW' && auth.user?.driver
      ? "Raqam buyurtma sizga berilgach ko'rinadi"
      : "Raqam faqat buyurtma tomonlariga ko'rinadi";
    return `<a class="person" href="#/u/${u.id}">${avatar(u.avatarUrl, u.fullName)}
      <div style="min-width:0"><div class="person-name">${esc(u.fullName)} ${verifiedBadge(u.verified)}</div>
        <div class="person-sub">${stars(u.rating, u.ratingCount)}</div></div></a>
      ${withVehicle && u.vehicle ? `<div class="small muted">${esc(u.vehicle.brand)} ${esc(u.vehicle.model || '')}, ${esc(BODY[u.vehicle.bodyType] || '')}
        <span class="plate" style="font-size:13px;margin-left:6px"><span>${esc(u.vehicle.plateNumber)}</span></span></div>` : ''}
      ${u.phone
        ? `<a class="btn btn-sm" href="tel:${esc(u.phone)}">${icon('phone')}${esc(phoneFmt(u.phone))}</a>`
        : `<p class="phone-lock">${icon('shield')}${esc(lockText)}</p>`}`;
  }

  function renderActions() {
    const box = $('#actions', main);
    const me = auth.user;
    const role = o.viewerRole;
    let html = '';

    if (role === 'SENDER') {
      if (o.status === 'NEW') {
        html += `<section class="panel"><div class="row-between"><h2>Takliflar</h2><span class="muted small">${o.offersCount} ta</span></div><div id="offers">${skeletons(2)}</div></section>`;
        const late = o.pickupDate && o.pickupDate < localISO();   // sanasi o'tgan e'lonni server shoshilinch qilmaydi
        if (late) {
          html += `<section class="panel stack-sm"><h2>Olib ketish sanasi o'tdi</h2>
            <p class="small muted">Sanasi o'tganiga 7 kun bo'lgach e'lon avtomatik yopiladi. Yuk hali jo'natilmagan bo'lsa, bu e'lonni bekor qilib, yangi sana bilan qayta joylang.</p></section>`;
        } else if (!o.urgent && !urgentHidden) {
          html += `<section class="panel stack-sm"><h2>Tezroq haydovchi topish</h2>
            <p class="small muted">Shoshilinch e'lon ro'yxatda doim eng yuqorida turadi va haydovchilarga qayta xabar boradi.</p>
            <button class="btn btn-primary btn-block" id="make-urgent">🔥 Shoshilinch qilish${urgentFee ? ' — ' + (urgentFree ? 'bepul' : esc(money(urgentFee))) : ''}</button></section>`;
        }
      }
      if (o.status === 'ACCEPTED' || o.status === 'IN_TRANSIT') {
        html += `<div class="code-card"><p>Topshirish kodi</p><span class="code">${esc(o.deliveryCode)}</span>
          <p>Bu kodni faqat qabul qiluvchiga ayting. Haydovchi yukni topshirganda shu kodni kiritadi.</p></div>`;
      }
      if (o.status === 'NEW' || o.status === 'ACCEPTED') {
        html += `<button class="btn btn-danger btn-block" id="cancel">Buyurtmani bekor qilish</button>`;
      }
    } else if (role === 'DRIVER') {
      if (o.status === 'ACCEPTED') {
        html += `<section class="panel stack-sm"><h2>Yukni olishga boring</h2>
          <p class="small muted">Navigatsiya yuk olish manziligacha yo'l ko'rsatadi, yuboruvchi esa sizni xaritada kuzatadi.</p>
          <a class="btn btn-route btn-lg btn-block" href="#/nav/${o.id}">${icon('nav')}Navigatsiyani boshlash</a>
          <button class="btn btn-primary btn-lg btn-block" id="pickup">Yukni oldim, yo'lga chiqdim</button>
          <button class="btn btn-danger btn-block" id="cancel">Voz kechish</button>
          ${o.commission ? `<p class="tiny muted">Bu buyurtma uchun balansingizdan ${esc(money(o.commission))} komissiya yechilgan.</p>` : ''}</section>`;
      }
      if (o.status === 'IN_TRANSIT') {
        html += `<section class="panel stack-sm"><h2>Yuk yo'lda</h2>
          <a class="btn btn-route btn-lg btn-block" href="#/nav/${o.id}">${icon('nav')}Navigatsiya: topshirish manziliga</a>
          <form id="deliver-form" class="stack-sm" novalidate>
            <label class="field"><span>Qabul qiluvchidan olingan 4 xonali kod</span>
              <input class="input code-input" name="code" inputmode="numeric" maxlength="4" required></label>
            <button class="btn btn-ok btn-lg btn-block" type="submit">Yukni topshirdim</button></form></section>`;
      }
    } else if (o.status === 'NEW' && me && o.sender.id !== me.id) {
      if (!me.driver) {
        html += `<section class="panel stack-sm"><h2>Bu buyurtmani olmoqchimisiz?</h2>
          <p class="small muted">Taklif yuborish uchun profilingizga mashina ma'lumotlarini kiriting.</p>
          <a class="btn btn-primary btn-block" href="#/profile?vehicle=1">Mashina qo'shish</a></section>`;
      } else if (o.myOffer) {
        html += `<section class="panel stack-sm"><h2>Sizning taklifingiz</h2>
          <p style="font-size:22px;font-weight:700" class="num">${esc(money(o.myOffer.price))}</p>
          ${o.myOffer.comment ? `<p class="small">${esc(o.myOffer.comment)}</p>` : ''}
          <p class="small muted">Yuboruvchi tanlashini kuting — javob Telegramga keladi.</p>
          <div class="row"><button class="btn btn-sm" id="edit-offer">Narxni o'zgartirish</button>
          <button class="btn btn-danger btn-sm" id="withdraw">Taklifni qaytarish</button></div></section>`;
      } else {
        html += offerForm();
      }
    }
    if (o.status === 'DELIVERED' && (role === 'SENDER' || role === 'DRIVER')) {
      html += o.reviewedByMe
        ? `<section class="panel"><h2>Rahmat!</h2><p class="muted small" style="margin-top:6px">Siz bu buyurtmani baholadingiz.</p></section>`
        : `<section class="panel stack-sm"><h2>${role === 'SENDER' ? 'Haydovchini baholang' : 'Yuboruvchini baholang'}</h2>
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

  function offerForm(existing) {
    return `<section class="panel stack-sm" id="offer-box"><h2>${existing ? 'Taklifni yangilash' : 'Narx taklif qiling'}</h2>
      ${o.price && !existing ? `<button class="btn btn-primary btn-lg btn-block" id="take">${esc(money(o.price))} ga roziman, olaman</button>
        <p class="small muted" style="text-align:center">yoki o'z narxingizni taklif qiling</p>` : ''}
      <form id="offer-form" class="stack-sm" novalidate>
        <label class="field"><span>Narxingiz, so'm</span><input class="input" name="price" type="number" min="1000" step="1000" inputmode="numeric" value="${existing?.price || ''}" required></label>
        <label class="field"><span>Qachon olib keta olasiz?</span><select class="select" name="pickupInHours">
          <option value="1">1 soat ichida</option><option value="3">3 soat ichida</option><option value="24" selected>Bugun-ertaga</option><option value="72">3 kun ichida</option></select></label>
        <label class="field"><span>Izoh</span><textarea class="textarea" name="comment" maxlength="500" placeholder="Masalan: yuklashga yordam beraman, tentli Isuzu">${esc(existing?.comment || '')}</textarea></label>
        <button class="btn ${o.price && !existing ? '' : 'btn-primary'} btn-block" type="submit">Taklif yuborish</button></form>
      ${pr && freeLeft > 0 ? `<p class="tiny muted">🎁 Sizda yana <b>${freeLeft} ta</b> komissiyasiz buyurtma bor — bu buyurtma sizga berilsa, balansdan hech narsa yechilmaydi.</p>`
        : pr ? `<p class="tiny muted">Buyurtma sizga berilsa, balansingizdan komissiya yechiladi: narxning ${esc(num(pr.commissionPercent, 1))}%${pr.commissionMin ? `, kamida ${esc(money(pr.commissionMin))}` : ''}${o.price ? ` — shu narxda ${esc(money(commissionFor(pr, o.price)))}` : ''}.
        Yuboruvchi bekor qilsa, komissiya qaytariladi.</p>` : ''}</section>`;
  }

  function bindActions() {
    const on = (sel, fn) => { const el = $(sel, main); if (el) el.addEventListener('click', fn); };
    on('#cancel', async () => {
      const isDriver = o.viewerRole === 'DRIVER';
      const r = await confirmDialog(isDriver ? 'Buyurtmadan voz kechasizmi?' : 'Buyurtmani bekor qilasizmi?',
        isDriver ? "Buyurtma qayta e'lon qilinadi. Tez-tez voz kechish reytingingizga ta'sir qiladi."
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
    on('#make-urgent', async (e) => {
      const btn = e.currentTarget;   // await dan keyin currentTarget null bo'lib qoladi
      const r = await confirmDialog("E'lonni shoshilinch qilasizmi?",
        urgentFee && !urgentFree ? `${money(urgentFee)} balansingizdan yechiladi. E'lon ro'yxatda eng yuqoriga chiqadi.`
          : (urgentFree ? "Birinchi shoshilinch e'loningiz bepul. " : '') + "E'lon ro'yxatda eng yuqoriga chiqadi va haydovchilarga qayta xabar boradi.",
        { okText: 'Shoshilinch qilish' });
      if (!r) return;
      try { o = await busy(btn, () => post(`/orders/${id}/urgent`)); toast("E'lon shoshilinch qilindi", 'ok'); render(); } catch (err) { toast(err.message, 'err'); }
    });
    on('#take', async (e) => {
      const fee = freeLeft > 0 ? 0 : commissionFor(pr, o.price);
      const r = await confirmDialog('Buyurtmani olasizmi?', `${money(o.price)} narxda. Yuboruvchiga darhol xabar boradi.`
        + (fee ? ` Balansingizdan ${money(fee)} komissiya yechiladi.` : (pr && freeLeft > 0 ? ' Komissiya olinmaydi (yangi haydovchi bonusi).' : '')),
        { okText: 'Olaman' });
      if (!r) return;
      try { o = await busy($('#take', main), () => post(`/orders/${id}/take`)); toast('Buyurtma sizniki! Yuboruvchiga qo\'ng\'iroq qiling', 'ok'); render(); } catch (err) { toast(err.message, 'err'); }
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
            ${f.driver.vehicle ? `${esc(f.driver.vehicle.brand)} ${esc(f.driver.vehicle.model || '')}, ${esc(BODY[f.driver.vehicle.bodyType] || '')}${f.driver.vehicle.capacityKg ? ', ' + num(f.driver.vehicle.capacityKg) + ' kg gacha' : ''}` : ''}
            ${f.pickupInHours ? `<br>Olib ketadi: ${f.pickupInHours <= 3 ? f.pickupInHours + ' soat ichida' : f.pickupInHours <= 24 ? 'bugun-ertaga' : '3 kun ichida'}` : ''}
            ${f.comment ? `<br>«${esc(f.comment)}»` : ''}</div>
          <div class="offer-actions">
            <button class="btn btn-primary btn-sm" data-accept="${f.id}">Tanlash</button>
            <a class="btn btn-sm" href="tel:${esc(f.driver.phone)}">${icon('phone')}Qo'ng'iroq</a>
            <button class="btn btn-ghost btn-sm" data-reject="${f.id}">Rad etish</button>
          </div></div>`).join('')
        : `<p class="muted small" style="margin-top:10px">Hozircha taklif yo'q. Haydovchilarga xabar yuborildi — takliflar Telegramga ham keladi.</p>`;
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
    if (!from && !to) {
      el.innerHTML = `<div class="map-empty">Aniq manzillar xaritada belgilanmagan</div>`;
      return;
    }
    try {
      mapCtx = await createMap(el, { center: from || to, zoom: 10 });
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
          if (!list.children.length) list.innerHTML = `<p class="muted small" style="margin:auto;text-align:center" id="chat-empty">Yuk bo'yicha savollaringizni shu yerda yozing. Xabar Telegramga ham boradi.</p>`;
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
    form.addEventListener('submit', async (e) => {
      e.preventDefault();
      const text = form.text.value.trim();
      if (!text) return;
      form.text.value = '';
      try { await post(`/orders/${id}/messages`, { text }); pull(); } catch (err) { toast(err.message, 'err'); form.text.value = text; }
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
      const changed = fresh.status !== o.status || fresh.offersCount !== o.offersCount || fresh.driver?.id !== o.driver?.id;
      o = fresh;
      if (changed && !document.activeElement?.closest('form')) { driverMark = null; render(); }
      else updateDriverMark();
    } catch { /* */ }
  }, 10000);
  return () => { clearInterval(timer); clearInterval(chatTimer); };
}

export function reportDialog(targetUserId, orderId) {
  modal('Shikoyat yoki murojaat', `<form class="stack" novalidate>
      <p class="small muted">Murojaatingizni adminlar ko'rib chiqadi va javob Telegramga keladi.</p>
      <label class="field"><span>Nima bo'ldi?</span><textarea class="textarea" name="text" minlength="5" maxlength="1000" required></textarea></label>
      <button class="btn btn-primary" type="submit">Yuborish</button></form>`, {
    onMount: ({ el, close }) => {
      const form = $('form', el);
      form.addEventListener('submit', async (e) => {
        e.preventDefault();
        try {
          await busy(form.querySelector('[type=submit]'), () => post('/reports', { targetUserId, orderId, text: form.text.value.trim() }));
          toast('Murojaat yuborildi', 'ok');
          close();
        } catch (err) { showError(form, err); }
      });
    },
  });
}

// =====================================================================
// Mening buyurtmalarim
// =====================================================================
export async function myOrdersPage(main, _p, query) {
  const me = auth.user;
  let role = query.get('role') || (me.driver ? 'driver' : 'sender');
  let group = 'active', page = 0, items = [];
  main.innerHTML = `<div class="page page-narrow">
    <div class="page-head"><h1>Mening buyurtmalarim</h1><a class="btn btn-primary" href="#/new">${icon('plus')}Yangi buyurtma</a></div>
    <div class="row-between" style="margin-bottom:14px">
      <div class="seg" id="roles">
        <button data-role="sender">Yuborganlarim</button>
        <button data-role="driver">Yetkazayotganlarim</button>
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
        sender: ["Siz hali buyurtma bermagansiz", "Yukingizni yuborish uchun buyurtma yarating.", `<a class="btn btn-primary" href="#/new">Buyurtma berish</a>`],
        driver: ["Hozircha yetkazayotgan yukingiz yo'q", "Bozordan mos buyurtma toping va taklif yuboring.", `<a class="btn btn-primary" href="#/">Buyurtmalarni ko'rish</a>`],
        offers: ["Javob kutayotgan taklifingiz yo'q", "Buyurtmalarga narx taklif qilsangiz, shu yerda ko'rinadi.", `<a class="btn" href="#/">Buyurtmalarni ko'rish</a>`],
      }[role];
      $('#list', main).innerHTML = items.length ? `<div class="orders">${items.map(orderRow).join('')}</div>` : emptyState(...empty);
      $('#more', main).classList.toggle('hidden', data.page + 1 >= data.totalPages);
    } catch (e) { $('#list', main).innerHTML = emptyState("Yuklab bo'lmadi", e.message); }
  }
  sync();
  load();
}
