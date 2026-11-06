// Haydovchilarning reys e'lonlari: "Ertaga Kosonsoy → Toshkent, 07:00, 3 ta bo'sh o'rin" yoki «har kuni»
import { auth, get, post, put, del, config } from '../api.js';
import {
  $, $$, esc, icon, avatar, stars, toast, modal, confirmDialog, formData, showError, clearError, busy,
  bindRegionDistrict, bindRouteSearch, phoneFmt, emptyState, skeletons, bodyLabel, bodyCanon, verifiedBadge, tripWhen,
  money, date, dayWord,
} from '../ui.js';
import { share, siteLink } from '../share.js';
import { orderRow, commissionFor } from './orders.js';

/** Yo'lovchi olishi mumkin bo'lgan mashinalar — bo'sh o'rinlar shular uchun so'raladi */
const SEATED = new Set(['SEDAN', 'FURGON', 'OTHER']);

function tripRow(t, mine) {
  const d = t.driver;
  return `<div class="order-row">
    <div class="order-top"><div class="order-title">${t.daily
      ? `<span class="kind-tag k-DAILY">${icon('route')}Har kuni</span>${esc(String(t.departAt || '').slice(11, 16))}`
      : esc(tripWhen(t))}</div></div>
    <div class="route">
      <div class="route-end"><b>${esc(t.fromRegion)}</b><span>${esc(t.fromDistrict || '')}</span></div>
      <div class="road" aria-hidden="true"><i></i></div>
      <div class="route-end to"><b>${esc(t.toRegion)}</b><span>${esc(t.toDistrict || '')}</span></div>
    </div>
    <div class="meta">
      ${t.priceNote ? `<span><b>${esc(t.priceNote)}</b></span>` : ''}
      ${t.seats ? `<span><b>${t.seats}</b> ta bo'sh o'rin</span>` : ''}
      ${d.vehicle ? `<span>${esc(d.vehicle.brand)}${d.vehicle.model ? ' ' + esc(d.vehicle.model) : ''}, ${esc(bodyLabel(d.vehicle.bodyType))}</span>` : ''}
      ${!t.active ? '<span class="pill st-CANCELLED">Yopilgan</span>' : ''}
    </div>
    ${t.comment ? `<p class="small">${esc(t.comment)}</p>` : ''}
    ${mine && t.active ? planChip(t) : ''}
    <div class="row-between">
      <a class="person" href="#/u/${d.id}">${avatar(d.avatarUrl, d.fullName)}<div><div class="person-name">${esc(d.fullName)} ${verifiedBadge(d.verified)}</div><div class="person-sub">${stars(d.rating, d.ratingCount)}</div></div></a>
      ${mine
        ? `<div class="row">${t.active ? `<button class="btn btn-sm btn-route" data-share="${t.id}">${icon('share')}Ulashish</button>` : ''}
            <button class="btn btn-sm" data-edit="${t.id}">${icon('edit')}O'zgartirish</button>
            <button class="btn btn-sm" data-toggle="${t.id}">${t.active ? 'Yopish' : 'Qayta ochish'}</button>
            <button class="btn btn-ghost btn-sm" data-del="${t.id}">O'chirish</button></div>`
        : d.phone ? `<a class="btn btn-route btn-sm" href="tel:${esc(d.phone)}">${icon('phone')}${esc(phoneFmt(d.phone))}</a>` : ''}
    </div></div>`;
}

/** «Mening reyslarim»: reysga mos to'plam bo'lsa — «🧩 Reysga yig'ish: 3 yo'lovchi · 2 pochta» havolasi */
function planChip(t) {
  const p = t.plan;
  if (!p || !(p.passengers + p.parcels)) return '';
  const parts = [];
  if (p.passengers) parts.push(`${p.passengers} yo'lovchi${p.people !== p.passengers ? ` (${p.people} kishi)` : ''}`);
  if (p.parcels) parts.push(`${p.parcels} pochta`);
  return `<a class="plan-chip" href="#/trips/${t.id}/plan">${icon('route')}<span><b>Reysga yig'ish:</b> ${esc(parts.join(' · '))} mos</span><span class="plan-go" aria-hidden="true">›</span></a>`;
}

export async function tripsPage(main, _p, query) {
  const me = auth.user;
  const q = query instanceof URLSearchParams ? query : new URLSearchParams();
  const qid = (k) => (/^\d{1,18}$/.test(q.get(k) || '') ? q.get(k) : '');
  let tab = 'all', page = 0, items = [], loadSeq = 0;
  main.innerHTML = `<div class="page page-narrow">
    <div class="page-head"><div><h1>Reyslar</h1><p class="muted">Yo'lga chiqayotgan haydovchilar — ${me?.region ? 'avval sizning hududingizdan. ' : ''}Yo'lovchi, pochta yoki yukingiz bo'lsa — qo'ng'iroq qiling.</p></div>
      ${me?.driver ? `<button class="btn btn-primary" id="new-trip">${icon('plus')}Reys e'lon qilish</button>` : ''}</div>
    ${me?.driver ? `<div class="seg" style="margin-bottom:14px" id="tabs"><button class="on" data-t="all">Barcha reyslar</button><button data-t="mine">Mening reyslarim</button></div>` : ''}
    <form class="route-search" id="f" style="margin:0 0 16px">
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
    <div id="list">${skeletons()}</div>
    <div class="row" style="justify-content:center;margin-top:16px"><button class="btn hidden" id="more">Yana ko'rsatish</button></div>
    ${me && !me.driver ? `<p class="small muted" style="text-align:center;margin-top:18px">Haydovchimisiz? <a href="#/profile?vehicle=1">Mashinangizni qo'shing</a> — reys e'lon qilsangiz, yo'nalishingizdagi yo'lovchi va pochtalar haqida xabar olasiz.</p>` : ''}
    <p class="small" style="text-align:center;margin-top:12px"><a href="#/drivers">Barcha haydovchilar ro'yxati</a></p>
  </div>`;
  const f = $('#f', main);
  const search = await bindRouteSearch(f, {
    from: { regionId: qid('from'), districtId: qid('fd') },
    to: { regionId: qid('to'), districtId: qid('td') },
    onChange: () => load(),
  });

  async function load(more) {
    if (more) page++; else { page = 0; items = []; }
    const my = ++loadSeq;
    const list = $('#list', main);
    try {
      if (tab === 'mine') {
        items = await get('/trips/my');
        $('#more', main).classList.add('hidden');
      } else {
        const data = await get('/trips', { ...search.values(), page });
        if (my !== loadSeq) return;
        items = items.concat(data.items);
        $('#more', main).classList.toggle('hidden', data.page + 1 >= data.totalPages);
      }
      if (my !== loadSeq) return;
      list.innerHTML = items.length ? `<div class="orders">${items.map(t => tripRow(t, tab === 'mine')).join('')}</div>`
        : emptyState(tab === 'mine' ? "Sizda reys yo'q" : "Bu yo'nalishda reys yo'q",
          tab === 'mine' ? "Qaysi yo'nalishga qachon ketayotganingizni e'lon qiling — shu yo'nalishdagi yo'lovchi va yuk egalariga xabar boradi."
            : "Boshqa yo'nalishni tanlang yoki e'lon bering — mos haydovchilarga xabar boradi.",
          tab === 'mine' ? '' : `<a class="btn btn-primary" href="#/new">E'lon berish</a>`);
      bindMine();
    } catch (e) { if (my === loadSeq) list.innerHTML = emptyState("Yuklab bo'lmadi", e.message); }
  }

  function bindMine() {
    $$('[data-share]', main).forEach(b => b.addEventListener('click', () => shareTrip(items.find(t => t.id === Number(b.dataset.share)))));
    $$('[data-edit]', main).forEach(b => b.addEventListener('click', () => tripModal(items.find(t => t.id === Number(b.dataset.edit)), () => load())));
    $$('[data-toggle]', main).forEach(b => b.addEventListener('click', async () => {
      const t = items.find(x => x.id === Number(b.dataset.toggle));
      try { await post(`/trips/${t.id}/${t.active ? 'close' : 'open'}`); load(); } catch (e) { toast(e.message, 'err'); }
    }));
    $$('[data-del]', main).forEach(b => b.addEventListener('click', async () => {
      const r = await confirmDialog("Reysni o'chirasizmi?", '', { danger: true, okText: "O'chirish" });
      if (!r) return;
      try { await del(`/trips/${b.dataset.del}`); load(); } catch (e) { toast(e.message, 'err'); }
    }));
  }

  f.addEventListener('submit', (e) => { e.preventDefault(); load(); });
  $('#more', main).addEventListener('click', () => load(true));
  const showMine = () => {
    tab = 'mine';
    $$('#tabs button', main).forEach(x => x.classList.toggle('on', x.dataset.t === 'mine'));
    f.classList.add('hidden');
    load();
  };
  $$('#tabs button', main).forEach(b => b.addEventListener('click', () => {
    tab = b.dataset.t;
    $$('#tabs button', main).forEach(x => x.classList.toggle('on', x === b));
    f.classList.toggle('hidden', tab === 'mine');
    load();
  }));
  $('#new-trip', main)?.addEventListener('click', () => tripModal(null, showMine));
  // Botdagi «Ko'rish» (reys botdan e'lon qilingan): "#/trips?mine=1" — darhol «Mening reyslarim»
  if (q.get('mine') === '1' && me?.driver) showMine();
  else load();
  // Botdagi «Reys e'lon qilish» tugmasi: "#/trips?new=1&fr=..&fd=..&tr=..&td=..&d=..&s=.." — forma to'ldirilgan holda ochiladi
  if (q.get('new') === '1') {
    if (me?.driver) {
      const d = q.get('d') || '';
      const s = Number(q.get('s'));
      tripModal(null, showMine, {
        fromRegionId: qid('fr'), fromDistrictId: qid('fd'), toRegionId: qid('tr'), toDistrictId: qid('td'),
        date: /^\d{4}-\d{2}-\d{2}$/.test(d) ? d : null, seats: Number.isInteger(s) && s >= 1 && s <= 60 ? s : null,
      });
    } else if (me) {
      toast("Reys e'lon qilish uchun avval mashinangizni qo'shing");
    }
  }
}

/** Reysni ulashish: guruh yoki tanishlarga — shu yo'nalishdagi reyslar sahifasi */
function shareTrip(t) {
  if (!t) return;
  const from = t.fromDistrict || t.fromRegion, to = t.toDistrict || t.toRegion;
  const text = `🚗 ${tripWhen(t)}: ${from} → ${to}${t.seats ? `, ${t.seats} ta bo'sh o'rin` : ''}${t.priceNote ? `, ${t.priceNote}` : ''}. `
    + "Yo'lovchi, pochta yoki yuk olaman — YukGo'da qo'ng'iroq qiling:";
  const q = new URLSearchParams({ from: t.fromRegionId, to: t.toRegionId });
  if (t.fromDistrictId) q.set('fd', t.fromDistrictId);
  share({ title: 'YukGo', text, url: siteLink('#/trips?' + q) });
}

/** Mahalliy vaqt "YYYY-MM-DDTHH:mm" ko'rinishida (datetime-local uchun) */
function localInput(d) {
  const x = new Date(d);
  x.setMinutes(x.getMinutes() - x.getTimezoneOffset());
  return x.toISOString().slice(0, 16);
}

/**
 * Reys formasi: qayerdan, qayerga, qachon (yoki «har kuni» + soati), bo'sh o'rinlar (yengil mashina va furgonda),
 * narx va izoh. pre — botdan kelgan to'ldirilgan qiymatlar.
 */
function tripModal(t, onSaved, pre = {}) {
  const me = auth.user;
  const tomorrow = new Date(Date.now() + 86400000); tomorrow.setHours(7, 0, 0, 0);
  const start = pre.date ? new Date(pre.date + 'T07:00:00') : tomorrow;
  const at = t ? t.departAt.slice(0, 16) : localInput(start);
  const seated = SEATED.has(bodyCanon(me?.vehicle?.bodyType) || 'OTHER');
  modal(t ? 'Reysni tahrirlash' : "Reys e'lon qilish", `<form class="stack" novalidate>
      <div class="place-pick"><span class="place-label">Qayerdan</span>
        <div class="pair"><select class="select" name="fromRegionId" required aria-label="Qayerdan: viloyat"></select>
          <select class="select" name="fromDistrictId" aria-label="Qayerdan: tuman"></select></div></div>
      <div class="place-pick is-to"><span class="place-label">Qayerga</span>
        <div class="pair"><select class="select" name="toRegionId" required aria-label="Qayerga: viloyat"></select>
          <select class="select" name="toDistrictId" aria-label="Qayerga: tuman"></select></div></div>
      <label class="check"><input type="checkbox" name="daily" ${t?.daily ? 'checked' : ''}>
        <span><b>Har kuni qatnayman</b><br><span class="small muted">Masalan, har kuni ertalab Kosonsoy → Toshkent. Reys o'zingiz yopguningizcha turadi.</span></span></label>
      <label class="field" id="when-at"><span>Jo'nash vaqti</span><input class="input" name="departAt" type="datetime-local" value="${esc(at)}" required></label>
      <label class="field hidden" id="when-time"><span>Odatda jo'nash soati</span><input class="input" name="departTime" type="time" value="${esc(at.slice(11, 16))}"></label>
      ${seated ? `<label class="field"><span>Bo'sh o'rinlar <span class="muted">(ixtiyoriy)</span></span><input class="input" name="seats" type="number" min="1" max="60" value="${t?.seats ?? pre.seats ?? ''}" inputmode="numeric" placeholder="3"></label>` : ''}
      <label class="field"><span>Narx <span class="muted">(ixtiyoriy)</span></span><input class="input" name="priceNote" maxlength="100" value="${esc(t?.priceNote || '')}" placeholder="Masalan: bir kishi 150 000, pochta 30 000"></label>
      <label class="field"><span>Izoh <span class="muted">(ixtiyoriy)</span></span><textarea class="textarea" name="comment" maxlength="500" rows="2" placeholder="Qayerdan olib ketasiz, qaysi yo'l bilan borasiz">${esc(t?.comment || '')}</textarea></label>
      <p class="tiny muted">E'lon qilganingizdan keyin shu yo'nalishda yo'lovchi, pochta yoki yuk e'lon qilganlarga xabar boradi, yangi mos e'lonlar esa sizga keladi.</p>
      <button class="btn btn-primary" type="submit">${t ? 'Saqlash' : "E'lon qilish"}</button></form>`, {
    onMount: async ({ el, close }) => {
      const form = $('form', el);
      await Promise.all([
        bindRegionDistrict(form.fromRegionId, form.fromDistrictId, {
          // Tahrirlashda — reysning o'zidagi joy (tumansiz bo'lsa ham), yangisida — uy manzili
          regionId: t ? t.fromRegionId : (pre.fromRegionId || me.region?.id),
          districtId: t ? t.fromDistrictId : (pre.fromRegionId ? pre.fromDistrictId : me.district?.id),
          allowEmptyDistrict: true,
        }),
        bindRegionDistrict(form.toRegionId, form.toDistrictId, {
          regionId: t?.toRegionId ?? pre.toRegionId, districtId: t?.toDistrictId ?? pre.toDistrictId, allowEmptyDistrict: true,
        }),
      ]);
      const syncDaily = () => {
        const daily = form.daily.checked;
        $('#when-at', el).classList.toggle('hidden', daily);
        $('#when-time', el).classList.toggle('hidden', !daily);
      };
      form.daily.addEventListener('change', syncDaily);
      syncDaily();
      form.addEventListener('submit', async (e) => {
        e.preventDefault();
        clearError(form);
        const d = formData(form);
        if (!d.fromRegionId || !d.toRegionId) return showError(form, { message: 'Qayerdan va qayerga — viloyatni tanlang' });
        let departAt = d.departAt;
        if (form.daily.checked) {
          // Har kuni: sana — bugun, soati — tanlangani (server faqat soatni ko'rsatadi)
          const now = new Date();
          departAt = localInput(now).slice(0, 10) + 'T' + (d.departTime || '07:00');
        }
        if (!departAt) return showError(form, { message: "Jo'nash vaqtini kiriting" });
        const body = {
          fromRegionId: Number(d.fromRegionId) || null, toRegionId: Number(d.toRegionId) || null,
          fromDistrictId: Number(d.fromDistrictId) || null, toDistrictId: Number(d.toDistrictId) || null,
          departAt: departAt + ':00', daily: form.daily.checked,
          seats: d.seats ? Math.round(d.seats) : null, priceNote: d.priceNote, comment: d.comment,
        };
        try {
          const saved = await busy(form.querySelector('[type=submit]'), () => t ? put(`/trips/${t.id}`, body) : post('/trips', body));
          close();
          if (!t && saved?.matches > 0) {
            matchesNotice(saved);
          } else {
            toast(t ? 'Saqlandi' : "Reys e'lon qilindi. Mos e'lonlar chiqsa — sizga xabar keladi", 'ok');
          }
          onSaved();
        } catch (err) { showError(form, err); }
      });
    },
  });
}

/** Yangi reysga mos ochiq e'lonlar bor — reysga yig'ishga (bo'lsa) yoki ularni ko'rishga taklif */
function matchesNotice(t) {
  const q = new URLSearchParams({ from: t.fromRegionId, to: t.toRegionId });
  const p = t.plan;
  const fit = p && (p.passengers + p.parcels) > 0;
  const parts = [];
  if (fit && p.passengers) parts.push(`<b>${p.passengers} yo'lovchi</b>${p.people !== p.passengers ? ` (${p.people} kishi)` : ''}`);
  if (fit && p.parcels) parts.push(`<b>${p.parcels} ta pochta</b>`);
  modal("Reys e'lon qilindi", `<div class="stack">
      <p>Shu yo'nalishda <b>${t.matches} ta</b> e'lon haydovchi kutyapti — egalariga siz haqingizda xabar yuborildi.</p>
      ${fit ? `<div class="plan-hint">${icon('route')}<span>Reysingizga hozir ${parts.join(' va ')} mos — hammasini bitta reysda olib ketishingiz mumkin.</span></div>
        <a class="btn btn-primary btn-block" href="#/trips/${t.id}/plan" data-close>Reysni yig'ish</a>
        <a class="btn btn-block" href="#/?${q}" data-close>Barcha e'lonlar</a>`
        : `<a class="btn btn-primary btn-block" href="#/?${q}" data-close>E'lonlarni ko'rish</a>`}
      <button class="btn btn-ghost btn-block" data-close>Keyinroq</button></div>`);
}

// =====================================================================
// Reysga yig'ish: bitta reysda olib ketiladigan yo'lovchilar (bo'sh o'rinlargacha) va 2 tagacha pochta
// =====================================================================
export async function tripPlanPage(main, { id }) {
  const cfg = await get('/config').catch(() => config());
  const pr = cfg?.pricing?.enabled ? cfg.pricing : null;
  const wallet = pr ? await get('/wallet').catch(() => null) : null;
  const freeLeft = wallet?.freeDeliveriesLeft || 0;
  main.innerHTML = `<div class="page page-narrow">
    <a class="back-link" href="#/trips?mine=1">${icon('back')}Mening reyslarim</a>
    <div class="page-head"><div><h1>Reysga yig'ish</h1>
      <p class="muted">Bitta reysda — ko'proq daromad: bo'sh o'rinlaringizga mos yo'lovchilar va 2 tagacha pochta.</p></div></div>
    <div id="plan">${skeletons(3)}</div></div>`;
  const box = $('#plan', main);

  async function load() {
    let p;
    try { p = await get(`/trips/${id}/plan`); } catch (e) {
      if (document.body.contains(box)) box.innerHTML = emptyState("Reysni ochib bo'lmadi", e.message, `<a class="btn" href="#/trips?mine=1">Mening reyslarim</a>`);
      return;
    }
    if (!document.body.contains(box)) return;
    box.innerHTML = planHtml(p);
    $$('[data-take]', box).forEach(b => b.addEventListener('click', () => take(b, [...p.passengers, ...p.parcels].find(o => o.id === Number(b.dataset.take)))));
  }

  async function take(btn, o) {
    if (!o) return;
    const passenger = o.kind === 'PASSENGER';
    const fee = freeLeft > 0 ? 0 : (pr ? commissionFor(pr, o.price || 0) || pr.commissionMin || 0 : 0);
    const ok = await confirmDialog(passenger ? "Yo'lovchini olasizmi?" : 'Pochtani olasizmi?',
      (o.price ? `${money(o.price)} narxda. ` : 'Narxni telefonda kelishasiz. ')
      + `${passenger ? "Yo'lovchiga" : 'Yuboruvchiga'} darhol xabar boradi va ikkalangizga bir-biringizning raqami ochiladi.`
      + (fee ? ` Balansingizdan ${money(fee)} komissiya yechiladi.` : (pr && freeLeft > 0 ? ' Komissiya olinmaydi (yangi haydovchi bonusi).' : '')),
      { okText: 'Olaman' });
    if (!ok) return;
    try {
      await busy(btn, () => post(`/orders/${o.id}/take`));
      toast(passenger ? "Yo'lovchi sizniki! Qo'ng'iroq qilib kelishib oling" : "Pochta sizniki! Yuboruvchiga qo'ng'iroq qiling", 'ok');
      load();
    } catch (err) { toast(err.message, 'err'); load(); }
  }

  await load();
}

/** O'rinlar: mashinangizdagilar, tavsiya etilganlar va bo'sh qoladiganlari */
function seatBar(p) {
  const cells = [];
  for (let i = 0; i < Math.min(p.capacity, 20); i++) {
    const cls = i < p.seatsInCar ? 'in-car' : i < p.seatsInCar + p.seatsFilled ? 'pick' : '';
    cells.push(`<span class="seat ${cls}" aria-hidden="true">${icon('user')}</span>`);
  }
  const parts = [];
  if (p.seatsInCar) parts.push(`${p.seatsInCar} kishi mashinangizda`);
  if (p.seatsFilled) parts.push(`${p.seatsFilled} kishi tavsiya`);
  parts.push(p.freeSeats ? `${p.freeSeats} o'rin bo'sh qoladi` : "mashina to'ladi!");
  return `<div class="seat-bar" role="img" aria-label="${esc(`${p.capacity} o'rin: ${parts.join(', ')}`)}">${cells.join('')}</div>
    <p class="small muted">${p.capacity} o'rin: ${esc(parts.join(', '))}${p.capacityGuessed ? ` · <span class="warn-text">o'rinlar soni ko'rsatilmagan — ${p.capacity} ta deb hisobladik</span>` : ''}</p>`;
}

function planItems(list, label) {
  return list.map(o => `<div class="plan-item">${orderRow(o, { when: true })}
      <div class="plan-actions"><button class="btn btn-primary btn-sm" data-take="${o.id}">${icon('check')}Olaman</button>
        <a class="btn btn-sm" href="#/orders/${o.id}">Ko'rish</a></div></div>`).join('') || `<p class="small muted plan-empty">${esc(label)}</p>`;
}

function planHtml(p) {
  const from = [p.fromDistrict, p.fromRegion].filter(Boolean)[0];
  const to = [p.toDistrict, p.toRegion].filter(Boolean)[0];
  // «Ertaga, 7-noyabr · 07:00» (departAt — Toshkent vaqti, "YYYY-MM-DDTHH:mm")
  const iso = String(p.departAt || '').slice(0, 10);
  const word = iso ? dayWord(iso) : '', abs = iso ? date(iso + 'T00:00:00') : '';
  const when = p.departAt ? `${word === abs ? abs : `${word}, ${abs}`} · ${String(p.departAt).slice(11, 16)}` : '';
  const head = `<section class="panel stack-sm plan-trip">
      <div class="route">
        <div class="route-end"><b>${esc(from)}</b><span>${esc(p.fromDistrict ? p.fromRegion : '')}</span></div>
        <div class="road" aria-hidden="true"><i></i></div>
        <div class="route-end to"><b>${esc(to)}</b><span>${esc(p.toDistrict ? p.toRegion : '')}</span></div>
      </div>
      <p class="small">${icon('clock')}<b>${esc(p.daily ? 'Har kuni · ' : '')}${esc(when)}</b>${p.daily ? ' <span class="muted">(eng yaqin qatnov)</span>' : ''}</p>
      ${p.past ? '' : seatBar(p)}
      ${p.capacityGuessed && !p.past ? `<p class="tiny"><a href="#/trips?mine=1">Reysni tahrirlab</a> bo'sh o'rinlar sonini yozing — tavsiya aniqroq bo'ladi.</p>` : ''}
    </section>`;
  if (p.past) {
    return head + emptyState("Bu reys o'tib ketgan yoki yopilgan", "Yangi reys e'lon qiling — mos yo'lovchi va pochtalarni yig'ib beramiz.",
      `<a class="btn btn-primary" href="#/trips?new=1">Reys e'lon qilish</a>`);
  }
  const inCar = p.inCar.length ? `<section class="stack-sm"><h2 class="plan-h">${icon('check')}Mashinangizda</h2>
      <div class="orders">${p.inCar.map(o => orderRow(o, { when: true })).join('')}</div></section>` : '';
  const capNote = p.parcelCap > 0
    ? (p.parcelSlots === 0
      ? `<p class="tiny warn-text">Sizda hozir ${p.parcelsActive} ta pochta bor — bir vaqtda ko'pi bilan ${p.parcelCap} ta. Yetkazgach yana olasiz.</p>`
      : `<p class="tiny muted">Bir vaqtda ko'pi bilan ${p.parcelCap} ta pochta — qolganlari boshqa haydovchilarga ham qolsin.</p>`)
    : '';
  const more = p.morePassengers + p.moreParcels;
  const q = new URLSearchParams({ from: p.fromRegionId, to: p.toRegionId });
  const nothing = !p.passengers.length && !p.parcels.length;
  return head + inCar
    + (nothing ? `<div class="plan-hint">${icon('bell')}<span>Hozircha reysingizga mos yangi e'lon yo'q. Shu yo'nalishda yo'lovchi yoki pochta chiqishi bilan darhol xabar beramiz.</span></div>` : '')
    + `<section class="stack-sm"><h2 class="plan-h">${icon('user')}Yo'lovchilar${p.passengers.length ? ` <span class="muted">· ${p.seatsFilled} kishi</span>` : ''}</h2>
      ${planItems(p.passengers, p.freeSeats === 0 && !p.seatsFilled ? "Mashinangiz to'lgan." : "Hozircha mos yo'lovchi yo'q.")}</section>`
    + `<section class="stack-sm"><h2 class="plan-h">${icon('mail')}Pochtalar <span class="muted">· 2 tagacha</span></h2>
      ${planItems(p.parcels, p.parcelSlots === 0 ? 'Pochta chegarasiga yetdingiz.' : "Hozircha mos pochta yo'q.")}${capNote}</section>`
    + (more > 0 ? `<a class="btn btn-block" href="#/?${q}">Shu yo'nalishda yana ${more} ta e'lon — ko'rish</a>` : '');
}
