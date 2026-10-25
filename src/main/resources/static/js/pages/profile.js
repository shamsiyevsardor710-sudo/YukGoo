// Profil, ommaviy profil, bildirishnomalar, haydovchilar ro'yxati
import { auth, get, post, put, del, upload, config, regions } from '../api.js';
import {
  $, $$, esc, icon, avatar, stars, toast, modal, confirmDialog, formData, showError, clearError, busy,
  bindRegionDistrict, regionOptions, options, phoneFmt, date, ago, num, emptyState, skeletons, BODY, ROLE,
  money, dateTime, verifiedBadge,
} from '../ui.js';
import { phoneVerification } from './auth.js';
import { inviteBlock, verifyBlock } from './growth-ui.js';
import { reportDialog } from './orders.js';
import { refreshMe, logout, refreshUnread } from '../app.js';
import { openTopup, topupAvailable, topupReturn } from './topup.js';
import { digitalSalesBlocked } from '../platform.js';

// ---------- umumiy bloklar ----------
function vehicleBlock(v, editable) {
  if (!v) {
    return editable
      ? `<div class="stack-sm"><p class="muted">Mashina qo'shsangiz, buyurtmalarni olishingiz va reys e'lon qilishingiz mumkin. Mashina ma'lumotlari profilingizda hammaga ko'rinadi.</p>
         <button class="btn btn-primary" id="vehicle-edit">${icon('truck')}Mashina qo'shish</button></div>`
      : `<p class="muted">Mashina ma'lumotlari kiritilmagan.</p>`;
  }
  return `<div class="vehicle">
      <div class="avatar-wrap">
        ${v.photoUrl ? `<img class="vehicle-photo" src="${esc(v.photoUrl)}" alt="Mashina rasmi">` : `<div class="vehicle-photo">${icon('truck')}</div>`}
        ${editable ? `<label class="avatar-edit" title="Mashina rasmini yuklash">${icon('camera')}<input type="file" accept="image/*" id="vehicle-photo" class="sr"></label>` : ''}
      </div>
      <div class="stack-sm">
        <div><b style="font-size:18px">${esc(v.brand)} ${esc(v.model || '')}</b>${v.year ? ` <span class="muted">${v.year}</span>` : ''}</div>
        <div><span class="plate"><span>${esc(v.plateNumber)}</span><span class="uz">UZ</span></span></div>
        <dl class="kv small">
          <dt>Kuzov</dt><dd>${esc(BODY[v.bodyType] || '')}</dd>
          ${v.capacityKg ? `<dt>Yuk ko'tarish</dt><dd class="num">${num(v.capacityKg)} kg</dd>` : ''}
          ${v.volumeM3 ? `<dt>Hajmi</dt><dd class="num">${num(v.volumeM3, 1)} m³</dd>` : ''}
          ${v.color ? `<dt>Rangi</dt><dd>${esc(v.color)}</dd>` : ''}
        </dl>
        ${editable ? `<div class="row"><button class="btn btn-sm" id="vehicle-edit">${icon('edit')}O'zgartirish</button><button class="btn btn-ghost btn-sm" id="vehicle-remove">O'chirish</button></div>` : ''}
      </div></div>`;
}

async function reviewsBlock(el, userId) {
  try {
    const s = await get(`/users/${userId}/reviews`);
    if (!s.count) { el.innerHTML = `<p class="muted small">Hali baho yo'q.</p>`; return; }
    const max = Math.max(...Object.values(s.distribution), 1);
    el.innerHTML = `<div class="row" style="gap:24px;align-items:flex-start">
        <div><div style="font:600 40px/1 var(--display)" class="num">${s.average.toFixed(1)}</div>${stars(s.average)}<div class="small muted">${s.count} ta baho</div></div>
        <div class="dist" style="flex:1;min-width:180px">${Object.entries(s.distribution).sort((a, b) => b[0] - a[0]).map(([k, v]) =>
          `<div><span>${k}★</span><i><span style="width:${(v / max) * 100}%"></span></i><span class="num muted">${v}</span></div>`).join('')}</div>
      </div>
      <div class="stack-sm" style="margin-top:16px">${s.reviews.items.map(r => `<div class="panel-sunk">
        <div class="row-between"><a class="person" href="#/u/${r.authorId}">${avatar(r.authorAvatar, r.authorName, '')}
          <div><div class="person-name">${esc(r.authorName)}</div><div class="person-sub">${r.targetWasDriver ? 'haydovchi sifatida baholadi' : 'yuboruvchi sifatida baholadi'}, ${esc(ago(r.createdAt))}</div></div></a>
          ${stars(r.stars)}</div>
        ${r.comment ? `<p style="margin-top:8px">${esc(r.comment)}</p>` : ''}</div>`).join('')}</div>`;
  } catch (e) { el.innerHTML = `<p class="muted small">${esc(e.message)}</p>`; }
}

function heroBlock(u, editable) {
  const place = [u.region?.name, u.district?.name, editable ? u.village : null].filter(Boolean).join(', ');
  return `<section class="panel">
    <div class="profile-hero">
      <div class="avatar-wrap">${avatar(u.avatarUrl, u.fullName, 'avatar-xl')}
        ${editable ? `<label class="avatar-edit" title="Rasm yuklash">${icon('camera')}<input type="file" accept="image/*" id="avatar-input" class="sr"></label>` : ''}</div>
      <div style="min-width:0" class="stack-sm">
        <div class="row"><h1 style="font-size:clamp(22px,4vw,30px)">${esc(u.fullName)}</h1>
          ${verifiedBadge(u.verified)}
          ${u.role !== 'USER' ? `<span class="pill role-${u.role} plain">${esc(ROLE[u.role])}</span>` : ''}
          ${u.driver ? `<span class="pill ${u.available ? 'us-ACTIVE' : ''}">${u.available ? "Haydovchi, bo'sh" : 'Haydovchi, band'}</span>` : ''}</div>
        <div>${stars(u.rating, u.ratingCount)}</div>
        <div class="small muted">${place ? esc(place) + '. ' : ''}${esc(date(u.memberSince))} dan beri YukGo'da</div>
        ${u.bio ? `<p class="small">${esc(u.bio)}</p>` : ''}
      </div>
      <div class="actions row">
        ${u.phone
          ? `<a class="btn btn-route" href="tel:${esc(u.phone)}">${icon('phone')}${esc(phoneFmt(u.phone))}</a>`
          : `<p class="phone-lock">${icon('shield')}Raqam yashirin — buyurtma orqali bog'langanlarga ko'rinadi</p>`}
        ${editable ? `<button class="btn" id="edit-profile">${icon('edit')}Tahrirlash</button>` : ''}
      </div>
    </div>
    <div class="stat-row">
      <div><b>${(u.rating || 0).toFixed(1)}</b><span>Reyting (${u.ratingCount} baho)</span></div>
      <div><b>${num(u.ordersCreated)}</b><span>Bergan buyurtmalari</span></div>
      <div><b>${num(u.deliveriesCompleted)}</b><span>Yetkazgan yuklari</span></div>
    </div>
  </section>`;
}

// ---------- balans (daromad tizimi yoqilgan yoki balans bor bo'lsagina ko'rinadi) ----------
async function walletBlock(el) {
  try {
    const w = await get('/wallet');
    const p = w.pricing || {};
    if (!p.enabled && !w.balance) { el.innerHTML = ''; return; }
    // Click / Payme / Paynet ulangan bo'lsa — "Balansni to'ldirish" tugmasi
    const opts = p.enabled ? await get('/payments/options').catch(() => null) : null;
    const canTopup = topupAvailable(opts);
    el.innerHTML = `<section class="panel stack-sm">
      <div class="row-between"><h2>Balans</h2><span class="balance num">${esc(money(w.balance))}</span></div>
      ${p.enabled ? `<p class="small muted">Haydovchiga buyurtma berilganda komissiya yechiladi: narxning ${esc(num(p.commissionPercent, 1))}%${p.commissionMin ? `, kamida ${esc(money(p.commissionMin))}` : ''}${p.commissionMax ? `, ko'pi bilan ${esc(money(p.commissionMax))}` : ''}.${p.urgentFee && !digitalSalesBlocked() ? ` Shoshilinch e'lon — ${esc(money(p.urgentFee))}.` : ''}</p>` : ''}
      ${p.topupInfo ? `<div class="panel-sunk small" style="white-space:pre-line"><b>Balansni to'ldirish</b><br>${esc(p.topupInfo)}</div>` : ''}
      <div class="row">${canTopup ? `<button class="btn btn-primary btn-sm" id="wallet-topup">${icon('plus')}Balansni to'ldirish</button>` : ''}
        <button class="btn btn-sm" id="wallet-history">Balans tarixi</button></div>
    </section>`;
    $('#wallet-history', el).addEventListener('click', walletHistoryModal);
    if (canTopup) $('#wallet-topup', el).addEventListener('click', () => openTopup(opts, () => walletBlock(el)));
  } catch { el.innerHTML = ''; }
}

async function walletHistoryModal() {
  const data = await get('/wallet/transactions').catch(() => ({ items: [] }));
  const html = data.items.length
    ? `<div>${data.items.map(t => `<div class="tx-row">
        <div style="min-width:0"><b>${esc(t.typeLabel)}</b>${t.orderId ? ` · <a href="#/orders/${t.orderId}">#${t.orderId}</a>` : ''}
          <div class="tiny muted">${esc(dateTime(t.createdAt))}${t.note ? ' — ' + esc(t.note) : ''}</div></div>
        <div class="num tx-amt ${t.amount < 0 ? 'amt-neg' : 'amt-pos'}">${t.amount > 0 ? '+' : '−'}${esc(money(Math.abs(t.amount)))}</div>
      </div>`).join('')}</div>`
    : `<p class="muted small">Hali harakat yo'q.</p>`;
  modal('Balans tarixi', html, { onMount: ({ el, close }) => $$('a', el).forEach(a => a.addEventListener('click', close)) });
}

// =====================================================================
// O'z profilim
// =====================================================================
export async function profilePage(main, _p, query) {
  await refreshMe();
  const u = auth.user;
  const cfg = await config();
  main.innerHTML = `<div class="page">
    <div class="grid-2">
      <div class="stack">
        ${heroBlock(u, true)}
        <section class="panel"><h2>Mashinam</h2><div style="margin-top:14px" id="vehicle">${vehicleBlock(u.vehicle, true)}</div></section>
        <section class="panel"><h2>Baholar</h2><div id="reviews" style="margin-top:14px">${skeletons(2)}</div></section>
      </div>
      <div class="stack">
        <section class="panel stack-sm">
          <h2>Telegram</h2>
          ${u.telegramLinked
            ? `<p class="small">Hisobingiz botga ulangan. Takliflar, holat o'zgarishlari va xabarlar Telegramga keladi.</p>
               <button class="link-btn danger-link" id="tg-unlink" style="justify-self:start">Telegramdan uzish</button>`
            : `<p class="small muted">Botni ulang — yangi takliflar va buyurtmalar haqida darhol xabar olasiz. Botda «Telefon raqamni yuborish» tugmasini bosing.</p>
               <a class="btn btn-route" target="_blank" rel="noopener" href="https://t.me/${esc(cfg.botUsername)}?start=link">${icon('telegram')}@${esc(cfg.botUsername)} ni ulash</a>`}
        </section>
        <div id="wallet-slot"></div>
        <div id="verify-slot"></div>
        <div id="invite-slot"></div>
        ${u.driver ? `<section class="panel stack">
          <h2>Haydovchi sozlamalari</h2>
          <label class="setting"><span><b>Hozir bo'shman</b><br><span class="small muted">O'chirsangiz, yangi buyurtmalar haqida xabar kelmaydi</span></span>
            <span class="switch"><input type="checkbox" id="s-available" ${u.available ? 'checked' : ''}><span></span></span></label>
          <label class="setting"><span><b>Yangi buyurtmalar Telegramga</b><br><span class="small muted">Ishlaydigan viloyatlaringiz bo'yicha</span></span>
            <span class="switch"><input type="checkbox" id="s-notify" ${u.notifyNewOrders ? 'checked' : ''}><span></span></span></label>
          <div><b>Ishlaydigan viloyatlarim</b><p class="small muted" id="work-regions">…</p>
            <button class="btn btn-sm" style="margin-top:8px" id="edit-regions">Tanlash</button></div>
        </section>` : ''}
        <section class="panel stack-sm">
          <h2>Manzilim</h2>
          <dl class="kv small"><dt>Viloyat</dt><dd>${esc(u.region?.name || '—')}</dd><dt>Tuman</dt><dd>${esc(u.district?.name || '—')}</dd>
            <dt>Qishloq / mahalla</dt><dd>${esc(u.village || '—')}</dd><dt>Uy</dt><dd>${esc(u.house || '—')}</dd></dl>
          <p class="tiny muted">To'liq manzilingizni faqat siz ko'rasiz.</p>
        </section>
        <section class="panel stack-sm">
          <a class="btn btn-ghost" href="#/u/${u.id}">Profilim boshqalarga qanday ko'rinadi</a>
          <button class="btn btn-ghost" id="theme">Mavzu: ${themeLabel()}</button>
          <a class="btn btn-ghost" href="/privacy.html">Maxfiylik siyosati</a>
          <button class="btn btn-danger" id="logout">${icon('logout')}Chiqish</button>
          <button class="link-btn danger-link" id="delete-account">Hisobni o'chirish</button>
        </section>
      </div>
    </div></div>`;

  reviewsBlock($('#reviews', main), u.id);
  walletBlock($('#wallet-slot', main));
  // Click / Payme to'lov sahifasidan qaytildi (#/profile?topup=ID) — holatini ko'rsatamiz (bir marta)
  const topupId = query && query.get('topup');
  if (topupId && /^\d{1,18}$/.test(topupId)) {
    query.delete('topup');
    try { history.replaceState(null, '', '#/profile'); } catch { /* */ }
    topupReturn(topupId, () => walletBlock($('#wallet-slot', main)));
  }
  verifyBlock($('#verify-slot', main), u);
  inviteBlock($('#invite-slot', main));

  $('#avatar-input', main).addEventListener('change', async (e) => {
    const f = e.target.files[0];
    if (!f) return;
    try { auth.user = await upload('/users/me/avatar', f); toast('Rasm yangilandi', 'ok'); profilePage(main, _p, query); refreshMe(); }
    catch (err) { toast(err.message, 'err'); }
  });
  $('#edit-profile', main).addEventListener('click', () => editProfileModal(() => profilePage(main, _p, query)));
  bindVehicle(main, () => profilePage(main, _p, query));
  $('#logout', main).addEventListener('click', () => logout());
  $('#tg-unlink', main)?.addEventListener('click', async () => {
    const ok = await confirmDialog('Telegramdan uzilsinmi?',
      "Buyurtmalar va takliflar haqidagi xabarlar Telegramga kelmay qoladi, YukGo Telegram ichida o'zi kirmaydi. "
      + "Qayta ulash uchun botda «Telefon raqamni yuborish» tugmasini bosing yoki Telegram ichida parol bilan kiring.",
      { okText: 'Uzish' });
    if (!ok) return;
    try {
      auth.user = await post('/users/me/telegram/unlink');
      toast('Telegramdan uzildi', 'ok');
      profilePage(main, _p, query);
    } catch (err) { toast(err.message, 'err'); }
  });
  $('#delete-account', main).addEventListener('click', deleteAccountModal);
  $('#theme', main).addEventListener('click', (e) => { cycleTheme(); e.target.textContent = 'Mavzu: ' + themeLabel(); });

  if (u.driver) {
    const saveSetting = async (body) => {
      try { auth.user = await put('/users/me/settings', body); toast('Saqlandi'); } catch (err) { toast(err.message, 'err'); }
    };
    $('#s-available', main).addEventListener('change', (e) => saveSetting({ available: e.target.checked }));
    $('#s-notify', main).addEventListener('change', (e) => saveSetting({ notifyNewOrders: e.target.checked }));
    const all = await regions();
    const showRegions = () => {
      const ids = auth.user.workRegionIds || [];
      $('#work-regions', main).textContent = ids.length
        ? all.filter(r => ids.includes(r.id)).map(r => r.name).join(', ')
        : "Hamma viloyatlar (cheklov yo'q)";
    };
    showRegions();
    $('#edit-regions', main).addEventListener('click', () => {
      const ids = new Set(auth.user.workRegionIds || []);
      modal('Ishlaydigan viloyatlar', `<form class="stack"><p class="small muted">Hech birini tanlamasangiz, barcha viloyatlardagi buyurtmalar haqida xabar olasiz.</p>
          <div class="stack-sm">${all.map(r => `<label class="check"><input type="checkbox" value="${r.id}" ${ids.has(r.id) ? 'checked' : ''}>${esc(r.name)}</label>`).join('')}</div>
          <button class="btn btn-primary" type="submit">Saqlash</button></form>`, {
        onMount: ({ el, close }) => {
          $('form', el).addEventListener('submit', async (e) => {
            e.preventDefault();
            const chosen = $$('input:checked', el).map(i => Number(i.value));
            await saveSetting({ workRegionIds: chosen });
            showRegions();
            close();
          });
        },
      });
    });
  }
  if (query?.get('vehicle') && !u.vehicle) vehicleModal(() => profilePage(main, _p, new URLSearchParams()));
}

function bindVehicle(main, rerender) {
  $('#vehicle-edit', main)?.addEventListener('click', () => vehicleModal(rerender));
  $('#vehicle-remove', main)?.addEventListener('click', async () => {
    const r = await confirmDialog("Mashina ma'lumotlarini o'chirasizmi?", "Shundan keyin buyurtma ola olmaysiz.", { danger: true, okText: "O'chirish" });
    if (!r) return;
    try { auth.user = await del('/users/me/vehicle'); rerender(); } catch (e) { toast(e.message, 'err'); }
  });
  $('#vehicle-photo', main)?.addEventListener('change', async (e) => {
    const f = e.target.files[0];
    if (!f) return;
    try { auth.user = await upload('/users/me/vehicle/photo', f); toast('Rasm yuklandi', 'ok'); rerender(); } catch (err) { toast(err.message, 'err'); }
  });
}

function vehicleModal(onSaved) {
  const v = auth.user.vehicle || {};
  modal(auth.user.vehicle ? "Mashina ma'lumotlari" : "Mashina qo'shish", `<form class="stack" novalidate>
      <div class="form-grid">
        <label class="field"><span>Markasi</span><input class="input" name="brand" value="${esc(v.brand || '')}" placeholder="Isuzu, Chevrolet, KAMAZ" required maxlength="40"></label>
        <label class="field"><span>Modeli</span><input class="input" name="model" value="${esc(v.model || '')}" placeholder="NPR, Labo" maxlength="40"></label>
        <label class="field"><span>Davlat raqami</span><input class="input" name="plateNumber" value="${esc(v.plateNumber || '')}" placeholder="01 A 123 BC" required maxlength="15"></label>
        <label class="field"><span>Kuzov turi</span><select class="select" name="bodyType" required>${options(BODY, v.bodyType || '', 'Tanlang')}</select></label>
        <label class="field"><span>Yuk ko'tarishi, kg</span><input class="input" name="capacityKg" type="number" min="1" value="${v.capacityKg ?? ''}" inputmode="numeric"></label>
        <label class="field"><span>Kuzov hajmi, m³</span><input class="input" name="volumeM3" type="number" min="0" step="0.1" value="${v.volumeM3 ?? ''}" inputmode="decimal"></label>
        <label class="field"><span>Rangi</span><input class="input" name="color" value="${esc(v.color || '')}" maxlength="30"></label>
        <label class="field"><span>Ishlab chiqarilgan yili</span><input class="input" name="year" type="number" min="1970" max="2100" value="${v.year ?? ''}" inputmode="numeric"></label>
      </div>
      <p class="small muted">Bu ma'lumotlar profilingizda hammaga ko'rinadi — yuboruvchilar shunga qarab tanlaydi.</p>
      <button class="btn btn-primary" type="submit">Saqlash</button></form>`, {
    onMount: ({ el, close }) => {
      const form = $('form', el);
      form.addEventListener('submit', async (e) => {
        e.preventDefault();
        clearError(form);
        try {
          auth.user = await busy(form.querySelector('[type=submit]'), () => put('/users/me/vehicle', formData(form)));
          toast('Saqlandi', 'ok');
          close();
          refreshMe();
          onSaved();
        } catch (err) { showError(form, err); }
      });
    },
  });
}

/** "Tahrirlash" — ism, familiya, manzil; telefon raqam; parol */
function editProfileModal(onSaved) {
  const u = auth.user;
  let stopVerify = null;
  modal('Profilni tahrirlash', `
    <div class="seg" id="tabs" style="margin-bottom:16px"><button class="on" data-t="info">Ma'lumotlar</button><button data-t="phone">Telefon raqam</button><button data-t="pass">Parol</button></div>
    <div data-pane="info">
      <form class="stack" novalidate>
        <div class="form-grid">
          <label class="field"><span>Ism</span><input class="input" name="firstName" value="${esc(u.firstName)}" required maxlength="60"></label>
          <label class="field"><span>Familiya</span><input class="input" name="lastName" value="${esc(u.lastName)}" required maxlength="60"></label>
          <label class="field full"><span>O'zim haqimda</span><textarea class="textarea" name="bio" maxlength="500" placeholder="Tajribangiz, qaysi yo'nalishlarda ishlaysiz">${esc(u.bio || '')}</textarea></label>
          <label class="field"><span>Viloyat</span><select class="select" name="regionId" required></select></label>
          <label class="field"><span>Tuman / shahar</span><select class="select" name="districtId" required></select></label>
          <label class="field"><span>Qishloq / mahalla</span><input class="input" name="village" value="${esc(u.village || '')}" required maxlength="120"></label>
          <label class="field"><span>Ko'cha, uy</span><input class="input" name="house" value="${esc(u.house || '')}" required maxlength="120"></label>
        </div>
        <button class="btn btn-primary" type="submit">Saqlash</button></form>
    </div>
    <div data-pane="phone" class="hidden">
      <p class="small muted" style="margin-bottom:12px">Hozirgi raqam: <b>${esc(phoneFmt(u.phone))}</b>. Yangi raqamga tasdiqlash kodi yuboriladi.</p>
      <div id="phone-flow"></div>
    </div>
    <div data-pane="pass" class="hidden">
      <form class="stack" novalidate>
        <label class="field"><span>Joriy parol</span><input class="input" name="oldPassword" type="password" autocomplete="current-password" required></label>
        <label class="field"><span>Yangi parol</span><input class="input" name="newPassword" type="password" minlength="6" autocomplete="new-password" required></label>
        <button class="btn btn-primary" type="submit">Parolni o'zgartirish</button></form>
    </div>`, {
    onMount: async ({ el, close }) => {
      $$('#tabs button', el).forEach(b => b.addEventListener('click', () => {
        $$('#tabs button', el).forEach(x => x.classList.toggle('on', x === b));
        $$('[data-pane]', el).forEach(p => p.classList.toggle('hidden', p.dataset.pane !== b.dataset.t));
        if (b.dataset.t === 'phone' && !stopVerify) {
          stopVerify = phoneVerification($('#phone-flow', el), {
            phoneLabel: 'Yangi telefon raqam',
            startFn: (phone) => post('/users/me/phone/start', { newPhone: phone }),
            verifyFn: async (requestId, code) => { auth.user = await post('/users/me/phone/confirm', { requestId, code }); },
            onDone: () => { toast("Telefon raqam o'zgartirildi", 'ok'); close(); refreshMe(); onSaved(); },
          });
        }
      }));
      const info = $('[data-pane=info] form', el);
      await bindRegionDistrict(info.regionId, info.districtId, { regionId: u.region?.id, districtId: u.district?.id });
      info.addEventListener('submit', async (e) => {
        e.preventDefault();
        clearError(info);
        const d = formData(info);
        try {
          auth.user = await busy(info.querySelector('[type=submit]'), () => put('/users/me', {
            ...d, regionId: Number(d.regionId) || null, districtId: Number(d.districtId) || null,
          }));
          toast('Profil yangilandi', 'ok');
          close();
          refreshMe();
          onSaved();
        } catch (err) { showError(info, err); }
      });
      const pass = $('[data-pane=pass] form', el);
      pass.addEventListener('submit', async (e) => {
        e.preventDefault();
        clearError(pass);
        try {
          const res = await busy(pass.querySelector('[type=submit]'), () => put('/users/me/password', formData(pass)));
          // Boshqa qurilmalardagi kirishlar bekor bo'ldi — shu qurilma yangi token bilan davom etadi
          if (res && res.token) { auth.token = res.token; auth.user = res.user; }
          toast("Parol o'zgartirildi. Boshqa qurilmalardagi kirishlar yopildi", 'ok');
          close();
        } catch (err) { showError(pass, err); }
      });
    },
  });
}

// ---------- Mavzu (yorug'/qorong'i) ----------
// Google Play talabi: foydalanuvchi hisobini ilovaning o'zida o'chira olishi kerak
function deleteAccountModal() {
  modal("Hisobni o'chirish", `<form class="stack" novalidate>
      <p>Ismingiz, telefon raqamingiz, manzilingiz, rasmlar, mashina ma'lumotlari va Telegram ulanishi o'chiriladi.
        Buyurtmalar va baholar tarixi «O'chirilgan foydalanuvchi» nomi bilan qoladi.</p>
      <p class="small muted">Bu amalni ortga qaytarib bo'lmaydi. Keyinroq shu raqam bilan qaytadan ro'yxatdan o'tishingiz mumkin.
        <a href="/delete-account.html">Batafsil</a></p>
      <label class="field"><span>Tasdiqlash uchun parolingiz</span>
        <input class="input" name="password" type="password" autocomplete="current-password" required></label>
      <button class="btn btn-danger" type="submit">Hisobni butunlay o'chirish</button></form>`, {
    onMount: ({ el, close }) => {
      const f = $('form', el);
      f.addEventListener('submit', async (e) => {
        e.preventDefault();
        clearError(f);
        if (!f.password.value) return showError(f, { message: 'Parolni kiriting' });
        try {
          await busy(f.querySelector('[type=submit]'), () => post('/users/me/delete', { password: f.password.value }));
          close();
          toast("Hisobingiz o'chirildi", 'ok');
          logout({ server: false });   // hisob endi yo'q — serverga chiqish so'rovi yuborilmaydi
        } catch (err) { showError(f, err); }
      });
    },
  });
}

function themeLabel() {
  const t = safeGet('yukgo_theme');
  return t === 'dark' ? "qorong'i" : t === 'light' ? "yorug'" : 'avtomatik';
}
function cycleTheme() {
  const t = safeGet('yukgo_theme');
  const next = !t ? 'dark' : t === 'dark' ? 'light' : '';
  try { next ? localStorage.setItem('yukgo_theme', next) : localStorage.removeItem('yukgo_theme'); } catch { /* */ }
  applyTheme();
}
function safeGet(k) { try { return localStorage.getItem(k); } catch { return null; } }
export function applyTheme() {
  const t = safeGet('yukgo_theme');
  if (t) document.documentElement.dataset.theme = t; else delete document.documentElement.dataset.theme;
}
applyTheme();

// =====================================================================
// Ommaviy profil
// =====================================================================
export async function publicProfilePage(main, { id }) {
  const u = await get('/users/' + id);
  const isMe = auth.user && auth.user.id === u.id;
  main.innerHTML = `<div class="page"><div class="grid-2">
    <div class="stack">${heroBlock(u, false)}
      <section class="panel"><h2>Baholar</h2><div id="reviews" style="margin-top:14px">${skeletons(2)}</div></section></div>
    <div class="stack">
      <section class="panel"><h2>Mashinasi</h2><div style="margin-top:14px">${vehicleBlock(u.vehicle, false)}</div></section>
      ${u.telegramLinked ? `<section class="panel panel-tight small">Telegram orqali tasdiqlangan raqam ✅</section>` : ''}
      ${isMe ? `<a class="btn" href="#/profile">Profilimni tahrirlash</a>` : `<button class="btn btn-ghost btn-sm" id="report">Shikoyat qilish</button>`}
    </div></div></div>`;
  reviewsBlock($('#reviews', main), u.id);
  $('#report', main)?.addEventListener('click', () => reportDialog(u.id, null));
}

// =====================================================================
// Bildirishnomalar
// =====================================================================
export async function notificationsPage(main) {
  main.innerHTML = `<div class="page page-narrow">
    <div class="page-head"><h1>Bildirishnomalar</h1><button class="btn btn-sm" id="read-all">Hammasini o'qilgan qilish</button></div>
    <div id="list">${skeletons()}</div></div>`;
  const load = async () => {
    const data = await get('/notifications');
    $('#list', main).innerHTML = data.items.length
      ? `<div class="orders">${data.items.map(n => `<a class="order-row" href="${esc(n.link || '#/notifications')}" data-id="${n.id}" style="${n.read ? '' : 'background:var(--signal-soft)'}">
          <div class="order-top"><b>${esc(n.title)}</b><span class="small muted">${esc(ago(n.createdAt))}</span></div>
          ${n.body ? `<p class="small">${esc(n.body)}</p>` : ''}</a>`).join('')}</div>`
      : emptyState("Bildirishnoma yo'q", 'Takliflar va buyurtma holati haqidagi xabarlar shu yerda paydo bo\'ladi.');
    $$('[data-id]', main).forEach(a => a.addEventListener('click', () => { post(`/notifications/${a.dataset.id}/read`).then(refreshUnread).catch(() => {}); }));
  };
  $('#read-all', main).addEventListener('click', async () => { await post('/notifications/read-all'); refreshUnread(); load(); });
  await load();
}

// =====================================================================
// Haydovchilar
// =====================================================================
export async function driversPage(main) {
  main.innerHTML = `<div class="page">
    <div class="page-head"><div><h1>Haydovchilar</h1><p class="muted">Avval hujjatlari tekshirilganlar, keyin reyting va yetkazgan yuklari bo'yicha.</p></div></div>
    <form class="filters" id="f" style="margin-bottom:16px">
      <select class="select" name="regionId" aria-label="Viloyat">${await regionOptions('', 'Barcha viloyatlar')}</select>
      <select class="select" name="bodyType" aria-label="Mashina turi">${options(BODY, '', 'Har qanday mashina')}</select>
      <input class="input" name="minCapacity" type="number" min="0" placeholder="Kamida necha kg" aria-label="Minimal yuk ko'tarish">
    </form>
    <div id="list">${skeletons()}</div></div>`;
  const f = $('#f', main);
  const load = async () => {
    const d = formData(f);
    try {
      const data = await get('/drivers', d);
      $('#list', main).innerHTML = data.items.length ? `<div class="orders">${data.items.map(u => `<div class="order-row">
          <div class="row-between"><a class="person" href="#/u/${u.id}">${avatar(u.avatarUrl, u.fullName)}
            <div style="min-width:0"><div class="person-name">${esc(u.fullName)} ${verifiedBadge(u.verified)}</div><div class="person-sub">${stars(u.rating, u.ratingCount)}</div></div></a>
            ${u.phone ? `<a class="btn btn-sm" href="tel:${esc(u.phone)}">${icon('phone')}${esc(phoneFmt(u.phone))}</a>` : ''}</div>
          <div class="meta">
            ${u.vehicle ? `<span><b>${esc(u.vehicle.brand)} ${esc(u.vehicle.model || '')}</b></span><span>${esc(BODY[u.vehicle.bodyType] || '')}</span>
              ${u.vehicle.capacityKg ? `<span>${num(u.vehicle.capacityKg)} kg gacha</span>` : ''}` : ''}
            <span><b>${u.deliveriesCompleted}</b> ta yetkazgan</span>
            ${u.region ? `<span>${esc(u.region.name)}</span>` : ''}
            <span>${u.available ? "Hozir bo'sh" : 'Band'}</span>
          </div></div>`).join('')}</div>`
        : emptyState('Mos haydovchi topilmadi', "Filtrlarni o'zgartirib ko'ring.");
    } catch (e) { $('#list', main).innerHTML = emptyState("Yuklab bo'lmadi", e.message); }
  };
  $$('[name]', f).forEach(el => el.addEventListener('change', load));
  f.addEventListener('submit', (e) => { e.preventDefault(); load(); });
  load();
}
