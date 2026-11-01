// Haydovchilarning reys e'lonlari: "Ertaga Kosonsoy → Toshkent, 07:00, 3 ta bo'sh o'rin" yoki «har kuni»
import { auth, get, post, put, del } from '../api.js';
import {
  $, $$, esc, icon, avatar, stars, toast, modal, confirmDialog, formData, showError, clearError, busy,
  bindRegionDistrict, bindRouteSearch, phoneFmt, emptyState, skeletons, bodyLabel, bodyCanon, verifiedBadge, tripWhen,
} from '../ui.js';

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
    <div class="row-between">
      <a class="person" href="#/u/${d.id}">${avatar(d.avatarUrl, d.fullName)}<div><div class="person-name">${esc(d.fullName)} ${verifiedBadge(d.verified)}</div><div class="person-sub">${stars(d.rating, d.ratingCount)}</div></div></a>
      ${mine
        ? `<div class="row"><button class="btn btn-sm" data-edit="${t.id}">${icon('edit')}O'zgartirish</button>
            <button class="btn btn-sm" data-toggle="${t.id}">${t.active ? 'Yopish' : 'Qayta ochish'}</button>
            <button class="btn btn-ghost btn-sm" data-del="${t.id}">O'chirish</button></div>`
        : d.phone ? `<a class="btn btn-route btn-sm" href="tel:${esc(d.phone)}">${icon('phone')}${esc(phoneFmt(d.phone))}</a>` : ''}
    </div></div>`;
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
  load();
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

/** Yangi reysga mos ochiq e'lonlar bor — ularni ko'rishga taklif */
function matchesNotice(t) {
  const q = new URLSearchParams({ from: t.fromRegionId, to: t.toRegionId });
  modal("Reys e'lon qilindi", `<div class="stack">
      <p>Shu yo'nalishda <b>${t.matches} ta</b> e'lon haydovchi kutyapti — egalariga siz haqingizda xabar yuborildi.</p>
      <a class="btn btn-primary btn-block" href="#/?${q}" data-close>E'lonlarni ko'rish</a>
      <button class="btn btn-block" data-close>Keyinroq</button></div>`);
}
