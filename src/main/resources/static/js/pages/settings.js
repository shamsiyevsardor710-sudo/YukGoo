// Sozlamalar — hammasi bir joyda: haydovchi sozlamalari, bildirishnomalar va Telegram, hisob, ko'rinish, maxfiylik
import { auth, get, post, put, config, regions } from '../api.js';
import { $, $$, esc, icon, toast, modal, confirmDialog, phoneFmt, KIND, KIND_ICON } from '../ui.js';
import { currentTheme, setTheme } from '../theme.js';
import { refreshMe, logout } from '../app.js';
import { editProfileModal, blockedListModal, deleteAccountModal } from './profile.js';

export async function settingsPage(main) {
  const route = location.hash.split('?')[0];
  await refreshMe();
  const u = auth.user;
  const cfg = await config();
  // Sahifa qayta chizilganda (rejim, Telegram) shu orada boshqa sahifaga o'tilgan bo'lsa — uni bosib ketmaymiz
  if (location.hash.split('?')[0] !== route) return;
  const again = () => settingsPage(main);
  main.innerHTML = `<div class="page page-narrow settings-page">
    <a class="back-link" href="#/profile">${icon('back')}Profil</a>
    <div class="page-head"><div><h1>Sozlamalar</h1>
      <p class="muted">Hammasi bir joyda: xabarlar, haydovchi sozlamalari, hisob va ko'rinish.</p></div></div>
    ${u.driver ? driverGroup(u) : becomeDriver()}
    ${notifyGroup(u, cfg)}
    ${accountGroup(u)}
    ${lookGroup()}
    ${privacyGroup()}
    <div class="stack-sm set-bottom">
      <button class="btn btn-danger btn-block" id="logout">${icon('logout')}Chiqish</button>
      <button class="link-btn danger-link" id="delete-account">Hisobni o'chirish</button>
    </div>
  </div>`;

  $('#logout', main).addEventListener('click', () => logout());
  $('#delete-account', main).addEventListener('click', deleteAccountModal);
  $('#blocked-list', main).addEventListener('click', blockedListModal);
  $$('[data-edit]', main).forEach(b => b.addEventListener('click', () => editProfileModal(again, b.dataset.edit)));
  $$('[data-theme-set]', main).forEach(b => b.addEventListener('click', () => {
    setTheme(b.dataset.themeSet);
    $$('[data-theme-set]', main).forEach(x => x.classList.toggle('on', x === b));
  }));
  $('#tg-unlink', main)?.addEventListener('click', async () => {
    const ok = await confirmDialog('Telegramdan uzilsinmi?',
      "Buyurtmalar va takliflar haqidagi xabarlar Telegramga kelmay qoladi, YukGo Telegram ichida o'zi kirmaydi. "
      + "Qayta ulash uchun botda «Telefon raqamni yuborish» tugmasini bosing yoki Telegram ichida parol bilan kiring.",
      { okText: 'Uzish' });
    if (!ok) return;
    try {
      auth.user = await post('/users/me/telegram/unlink');
      toast('Telegramdan uzildi', 'ok');
      again();
    } catch (err) { toast(err.message, 'err'); }
  });
  if (u.driver) await bindDriver(main);
}

// ---------- guruhlar ----------
function driverGroup(u) {
  const kinds = u.notifyKinds || [];
  return `<section class="set-group" aria-labelledby="g-driver">
    <h2 class="set-title" id="g-driver">${icon('truck')}Haydovchi</h2>
    <label class="set-row setting"><span class="set-text"><b>Hozir bo'shman</b>
        <span class="small muted">O'chirsangiz, yangi e'lonlar haqida xabar kelmaydi (reyslaringizga moslari bundan mustasno)</span></span>
      <span class="switch"><input type="checkbox" id="s-available" ${u.available ? 'checked' : ''}><span></span></span></label>
    <label class="set-row setting"><span class="set-text"><b>Yangi e'lonlar haqida xabar</b>
        <span class="small muted">Yo'nalishingiz va hududingiz bo'yicha — Telegramga yoki ilovaga; har kuni ertalab 7 da qisqa xulosa ham</span></span>
      <span class="switch"><input type="checkbox" id="s-notify" ${u.notifyNewOrders ? 'checked' : ''}><span></span></span></label>
    <div class="set-row set-col"><label class="setting"><span class="set-text"><b>Faqat reyslarimga mos e'lonlar</b>
        <span class="small muted">Yoqsangiz, faqat «Reyslar»da e'lon qilgan yo'nalishingizga mos e'lonlar haqida xabar keladi. Uyingiz va ishlaydigan viloyatlaringiz bo'yicha boshqa e'lonlar haqida — yo'q.</span></span>
        <span class="switch"><input type="checkbox" id="s-route-only" ${u.routeOnly ? 'checked' : ''}><span></span></span></label>
      <p class="tiny warn-note hidden" id="route-only-warn">Hozir faol reysingiz yo'q — yangi e'lonlar haqida xabar kelmaydi. <a href="#/trips?new=1">Reys e'lon qilish</a></p></div>
    <div class="set-row set-col"><b>Qaysi e'lonlar haqida xabar kelsin</b>
      <div class="kind-picks" id="s-kinds" role="group" aria-label="E'lon turlari">${Object.keys(KIND).map(k =>
        `<button type="button" class="kind-pick ${kinds.includes(k) ? 'on' : ''}" data-kind="${k}" aria-pressed="${kinds.includes(k)}">${icon(KIND_ICON[k])}${esc(KIND[k])}</button>`).join('')}</div>
      <p class="tiny muted">${u.notifyKindsChosen ? "O'zingiz tanlagansiz." : "Mashinangiz turiga qarab tanlangan — xohlasangiz o'zgartiring."}</p></div>
    <div class="set-row"><span class="set-text"><b>Ishlaydigan viloyatlarim</b><span class="small muted" id="work-regions">…</span></span>
      <button class="btn btn-sm" id="edit-regions">Tanlash</button></div>
    <a class="set-row set-link" href="#/trips?mine=1"><span class="set-text"><b>Mening reyslarim</b>
        <span class="small muted">Reys e'lon qiling — bo'sh o'rinlaringizga mos yo'lovchi va pochtalarni yig'ib beramiz</span></span>${icon('chevron')}</a>
  </section>`;
}

function becomeDriver() {
  return `<section class="set-group"><h2 class="set-title">${icon('truck')}Haydovchimisiz?</h2>
    <a class="set-row set-link" href="#/profile?vehicle=1"><span class="set-text"><b>Mashinangizni qo'shing</b>
        <span class="small muted">Yo'nalishingizga mos yo'lovchi, pochta va yuklar haqida xabar olasiz, reys e'lon qila olasiz</span></span>${icon('chevron')}</a>
  </section>`;
}

function notifyGroup(u, cfg) {
  const bot = esc(cfg?.botUsername || '');
  return `<section class="set-group"><h2 class="set-title">${icon('bell')}Bildirishnomalar</h2>
    <div class="set-row"><span class="set-text"><b>Telegram</b>
        <span class="small muted">${u.telegramLinked
          ? `Ulangan ✓ — takliflar, buyurtma holati va yangi e'lonlar @${bot} orqali rasm bilan keladi`
          : "Ulanmagan — botni ulang: xabarlar darhol keladi, botga yozgan e'loningiz esa saytga o'zi chiqadi"}</span></span>
      ${u.telegramLinked ? `<button class="btn btn-sm btn-ghost" id="tg-unlink">Uzish</button>`
        : `<a class="btn btn-sm btn-route" target="_blank" rel="noopener" href="https://t.me/${bot}?start=link">${icon('telegram')}Ulash</a>`}</div>
    <a class="set-row set-link" href="#/notifications"><span class="set-text"><b>Bildirishnomalar ro'yxati</b>
        <span class="small muted">Hammasini o'qilgan qilish yoki o'chirish</span></span>${icon('chevron')}</a>
  </section>`;
}

function accountGroup(u) {
  const place = [u.district?.name, u.region?.name].filter(Boolean).join(', ') || '—';
  return `<section class="set-group"><h2 class="set-title">${icon('user')}Hisob</h2>
    <div class="set-row"><span class="set-text"><b>Ism va manzil</b><span class="small muted">${esc(u.fullName)} · ${esc(place)}</span>
        <span class="tiny muted">Bosh sahifada avval shu tumandan, keyin viloyatingiz va yaqin hududlardan e'lonlar chiqadi.</span></span>
      <button class="btn btn-sm" data-edit="info" id="edit-place">${icon('edit')}O'zgartirish</button></div>
    <div class="set-row"><span class="set-text"><b>Telefon raqam</b><span class="small muted">${esc(phoneFmt(u.phone))}</span></span>
      <button class="btn btn-sm" data-edit="phone">O'zgartirish</button></div>
    <div class="set-row"><span class="set-text"><b>Parol</b><span class="small muted">O'zgartirsangiz, boshqa qurilmalardagi kirishlar yopiladi</span></span>
      <button class="btn btn-sm" data-edit="pass">O'zgartirish</button></div>
    <a class="set-row set-link" href="#/u/${u.id}"><span class="set-text"><b>Profilim boshqalarga qanday ko'rinadi</b></span>${icon('chevron')}</a>
  </section>`;
}

function lookGroup() {
  const t = currentTheme();
  return `<section class="set-group"><h2 class="set-title">${icon(t === 'dark' ? 'moon' : 'sun')}Ko'rinish</h2>
    <div class="set-row set-wrap"><span class="set-text"><b>Rejim</b><span class="small muted">Tanlov shu qurilmada eslab qolinadi</span></span>
      <div class="seg" role="group" aria-label="Rejim">
        <button data-theme-set="dark" class="${t === 'dark' ? 'on' : ''}">${icon('moon')}Kechki</button>
        <button data-theme-set="light" class="${t === 'light' ? 'on' : ''}">${icon('sun')}Kunduzgi</button></div></div>
  </section>`;
}

function privacyGroup() {
  return `<section class="set-group"><h2 class="set-title">${icon('shield')}Maxfiylik va hujjatlar</h2>
    <button type="button" class="set-row set-link" id="blocked-list"><span class="set-text"><b>Bloklanganlar</b>
        <span class="small muted">Blokdan chiqarish shu yerda</span></span>${icon('chevron')}</button>
    <a class="set-row set-link" href="/terms.html" data-doc><span class="set-text"><b>Foydalanish shartlari</b></span>${icon('chevron')}</a>
    <a class="set-row set-link" href="/privacy.html" data-doc><span class="set-text"><b>Maxfiylik siyosati</b></span>${icon('chevron')}</a>
  </section>`;
}

// ---------- haydovchi sozlamalari ----------
async function bindDriver(main) {
  const saveSetting = async (body) => {
    try { auth.user = await put('/users/me/settings', body); toast('Saqlandi'); } catch (err) { toast(err.message, 'err'); }
  };
  $('#s-available', main).addEventListener('change', (e) => saveSetting({ available: e.target.checked }));
  $('#s-notify', main).addEventListener('change', (e) => saveSetting({ notifyNewOrders: e.target.checked }));
  // «Faqat reyslarimga mos»: faol reys bo'lmasa, xabar umuman kelmasligini ogohlantiramiz
  let hasTrip = null;
  const routeWarn = async () => {
    const warn = $('#route-only-warn', main);
    if (!warn) return;
    if (!auth.user?.routeOnly) { warn.classList.add('hidden'); return; }
    if (hasTrip === null) {
      const mine = await get('/trips/my').catch(() => null);
      const soon = Date.now() - 3 * 3600 * 1000;
      hasTrip = !mine || mine.some(t => t.active && (t.daily || new Date(t.departAt).getTime() > soon));
    }
    if (document.body.contains(warn)) warn.classList.toggle('hidden', hasTrip);
  };
  $('#s-route-only', main).addEventListener('change', async (e) => { await saveSetting({ routeOnly: e.target.checked }); routeWarn(); });
  routeWarn();
  // E'lon turlari: kamida bittasi tanlangan bo'lsin
  $$('#s-kinds .kind-pick', main).forEach(b => b.addEventListener('click', async () => {
    const on = $$('#s-kinds .kind-pick.on', main).map(x => x.dataset.kind);
    const next = b.classList.contains('on') ? on.filter(k => k !== b.dataset.kind) : [...on, b.dataset.kind];
    if (!next.length) { toast('Kamida bitta turni tanlang'); return; }
    b.classList.toggle('on');
    b.setAttribute('aria-pressed', b.classList.contains('on'));
    await saveSetting({ notifyKinds: next });
  }));
  const all = await regions();
  const showRegions = () => {
    const el = $('#work-regions', main);
    if (!el) return;
    const ids = auth.user.workRegionIds || [];
    el.textContent = ids.length
      ? `${auth.user.region?.name ? auth.user.region.name + ' (uyingiz), ' : ''}${all.filter(r => ids.includes(r.id) && r.id !== auth.user.region?.id).map(r => r.name).join(', ')}`.replace(/, $/, '')
      : `${auth.user.region?.name || "O'z viloyatingiz"} — uyingiz joylashgan viloyat (shu yerdan va shu yerga e'lonlar)`;
  };
  showRegions();
  $('#edit-regions', main).addEventListener('click', () => {
    const ids = new Set(auth.user.workRegionIds || []);
    modal('Ishlaydigan viloyatlar', `<form class="stack"><p class="small muted">O'z viloyatingizdan va unga keladigan e'lonlar haqida doim xabar olasiz. Boshqa qatnaydigan viloyatlaringizni ham belgilang — ulardan va ularga e'lonlar haqida ham xabar keladi. Reyslaringizga mos e'lonlar haqida esa doim xabar keladi.</p>
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
