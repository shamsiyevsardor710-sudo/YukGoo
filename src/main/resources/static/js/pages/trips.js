// Haydovchilarning reys e'lonlari: "Ertaga Toshkent → Samarqand, 2 t bo'sh joy"
import { auth, get, post, put, del } from '../api.js';
import {
  $, $$, esc, icon, avatar, stars, toast, modal, confirmDialog, formData, showError, clearError, busy,
  bindRegionDistrict, regionOptions, phoneFmt, dateTime, num, emptyState, skeletons, BODY,
} from '../ui.js';

function tripRow(t, mine) {
  const d = t.driver;
  return `<div class="order-row">
    <div class="order-top"><div class="order-title">${esc(dateTime(t.departAt))}</div>
      ${t.priceNote ? `<div class="price" style="font-size:15px">${esc(t.priceNote)}</div>` : ''}</div>
    <div class="route">
      <div class="route-end"><b>${esc(t.fromRegion)}</b><span>${esc(t.fromDistrict || '')}</span></div>
      <div class="road" aria-hidden="true"><i></i></div>
      <div class="route-end to"><b>${esc(t.toRegion)}</b><span>${esc(t.toDistrict || '')}</span></div>
    </div>
    <div class="meta">
      ${t.availableKg ? `<span>Bo'sh joy: <b>${num(t.availableKg)} kg</b></span>` : ''}
      ${t.availableM3 ? `<span><b>${num(t.availableM3, 1)} m³</b></span>` : ''}
      ${d.vehicle ? `<span>${esc(d.vehicle.brand)} ${esc(d.vehicle.model || '')}, ${esc(BODY[d.vehicle.bodyType] || '')}</span>` : ''}
      ${!t.active ? '<span class="pill st-CANCELLED">Yopilgan</span>' : ''}
    </div>
    ${t.comment ? `<p class="small">${esc(t.comment)}</p>` : ''}
    <div class="row-between">
      <a class="person" href="#/u/${d.id}">${avatar(d.avatarUrl, d.fullName)}<div><div class="person-name">${esc(d.fullName)}</div><div class="person-sub">${stars(d.rating, d.ratingCount)}</div></div></a>
      ${mine
        ? `<div class="row"><button class="btn btn-sm" data-edit="${t.id}">${icon('edit')}O'zgartirish</button>
            <button class="btn btn-sm" data-toggle="${t.id}">${t.active ? 'Yopish' : 'Qayta ochish'}</button>
            <button class="btn btn-ghost btn-sm" data-del="${t.id}">O'chirish</button></div>`
        : `<a class="btn btn-route btn-sm" href="tel:${esc(d.phone)}">${icon('phone')}${esc(phoneFmt(d.phone))}</a>`}
    </div></div>`;
}

export async function tripsPage(main) {
  const me = auth.user;
  let tab = 'all', page = 0, items = [];
  main.innerHTML = `<div class="page page-narrow">
    <div class="page-head"><div><h1>Reyslar</h1><p class="muted">Yo'lga chiqayotgan, mashinasida bo'sh joyi bor haydovchilar. Yukingizni yo'l-yo'lakay arzonroq jo'nating.</p></div>
      ${me?.driver ? `<button class="btn btn-primary" id="new-trip">${icon('plus')}Reys e'lon qilish</button>` : ''}</div>
    ${me?.driver ? `<div class="seg" style="margin-bottom:14px" id="tabs"><button class="on" data-t="all">Barcha reyslar</button><button data-t="mine">Mening reyslarim</button></div>` : ''}
    <form class="filters" id="f" style="margin:0 0 16px">
      <select class="select" name="fromRegion" aria-label="Qayerdan">${await regionOptions('', 'Qayerdan: hamma')}</select>
      <select class="select" name="toRegion" aria-label="Qayerga">${await regionOptions('', 'Qayerga: hamma')}</select>
      <input class="input" name="minKg" type="number" min="1" placeholder="Yukim, kg" aria-label="Yuk og'irligi">
    </form>
    <div id="list">${skeletons()}</div>
    <div class="row" style="justify-content:center;margin-top:16px"><button class="btn hidden" id="more">Yana ko'rsatish</button></div>
  </div>`;
  const f = $('#f', main);

  async function load(more) {
    if (more) page++; else { page = 0; items = []; }
    const list = $('#list', main);
    try {
      if (tab === 'mine') {
        items = await get('/trips/my');
        $('#more', main).classList.add('hidden');
      } else {
        const data = await get('/trips', { ...formData(f), page });
        items = items.concat(data.items);
        $('#more', main).classList.toggle('hidden', data.page + 1 >= data.totalPages);
      }
      list.innerHTML = items.length ? `<div class="orders">${items.map(t => tripRow(t, tab === 'mine')).join('')}</div>`
        : emptyState(tab === 'mine' ? "Sizda reys yo'q" : "Bu yo'nalishda reys yo'q",
          tab === 'mine' ? "Qaysi yo'nalishga qachon ketayotganingizni e'lon qiling — yuk egalari sizga qo'ng'iroq qiladi." : "Boshqa yo'nalishni tanlang yoki buyurtma bering.",
          tab === 'mine' ? '' : `<a class="btn btn-primary" href="#/new">Buyurtma berish</a>`);
      bindMine();
    } catch (e) { list.innerHTML = emptyState("Yuklab bo'lmadi", e.message); }
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

  $$('[name]', f).forEach(el => el.addEventListener('change', () => load()));
  f.addEventListener('submit', (e) => { e.preventDefault(); load(); });
  $('#more', main).addEventListener('click', () => load(true));
  $$('#tabs button', main).forEach(b => b.addEventListener('click', () => {
    tab = b.dataset.t;
    $$('#tabs button', main).forEach(x => x.classList.toggle('on', x === b));
    f.classList.toggle('hidden', tab === 'mine');
    load();
  }));
  $('#new-trip', main)?.addEventListener('click', () => tripModal(null, () => { tab = 'mine'; $$('#tabs button', main)[1]?.click(); }));
  load();
}

function tripModal(t, onSaved) {
  const local = (d) => { const x = new Date(d); x.setMinutes(x.getMinutes() - x.getTimezoneOffset()); return x.toISOString().slice(0, 16); };
  const tomorrow = new Date(Date.now() + 86400000); tomorrow.setHours(7, 0, 0, 0);
  modal(t ? 'Reysni tahrirlash' : "Reys e'lon qilish", `<form class="stack" novalidate>
      <div class="form-grid">
        <label class="field"><span>Qayerdan</span><select class="select" name="fromRegionId" required></select></label>
        <label class="field"><span>Tuman</span><select class="select" name="fromDistrictId"></select></label>
        <label class="field"><span>Qayerga</span><select class="select" name="toRegionId" required></select></label>
        <label class="field"><span>Tuman</span><select class="select" name="toDistrictId"></select></label>
        <label class="field full"><span>Jo'nash vaqti</span><input class="input" name="departAt" type="datetime-local" value="${t ? t.departAt.slice(0, 16) : local(tomorrow)}" required></label>
        <label class="field"><span>Bo'sh joy, kg</span><input class="input" name="availableKg" type="number" min="1" value="${t?.availableKg ?? ''}" inputmode="numeric"></label>
        <label class="field"><span>Bo'sh hajm, m³</span><input class="input" name="availableM3" type="number" min="0" step="0.1" value="${t?.availableM3 ?? ''}" inputmode="decimal"></label>
        <label class="field full"><span>Narx haqida</span><input class="input" name="priceNote" maxlength="100" value="${esc(t?.priceNote || '')}" placeholder="Masalan: 1 kg — 500 so'm yoki kelishamiz"></label>
        <label class="field full"><span>Izoh</span><textarea class="textarea" name="comment" maxlength="500">${esc(t?.comment || '')}</textarea></label>
      </div>
      <button class="btn btn-primary" type="submit">${t ? 'Saqlash' : "E'lon qilish"}</button></form>`, {
    onMount: async ({ el, close }) => {
      const form = $('form', el);
      const me = auth.user;
      await Promise.all([
        bindRegionDistrict(form.fromRegionId, form.fromDistrictId, { regionId: t?.fromRegionId ?? me.region?.id, allowEmptyDistrict: true }),
        bindRegionDistrict(form.toRegionId, form.toDistrictId, { regionId: t?.toRegionId, allowEmptyDistrict: true }),
      ]);
      form.addEventListener('submit', async (e) => {
        e.preventDefault();
        clearError(form);
        const d = formData(form);
        const body = {
          ...d, fromRegionId: Number(d.fromRegionId) || null, toRegionId: Number(d.toRegionId) || null,
          fromDistrictId: Number(d.fromDistrictId) || null, toDistrictId: Number(d.toDistrictId) || null,
          departAt: d.departAt ? d.departAt + ':00' : null,
        };
        try {
          await busy(form.querySelector('[type=submit]'), () => t ? put(`/trips/${t.id}`, body) : post('/trips', body));
          toast(t ? 'Saqlandi' : "Reys e'lon qilindi", 'ok');
          close();
          onSaved();
        } catch (err) { showError(form, err); }
      });
    },
  });
}
