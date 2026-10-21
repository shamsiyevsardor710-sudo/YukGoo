// Boshqaruv paneli: admin va ega (owner)
import { auth, get, post, put, del, config } from '../api.js';
import {
  $, $$, esc, avatar, toast, confirmDialog, busy, showError, clearError, money, phoneFmt, dateTime, ago, num,
  options, statusPill, emptyState, skeletons, ROLE, USTATUS, STATUS,
} from '../ui.js';

export async function adminPage(main) {
  const owner = auth.isOwner;
  let tab = 'stats';
  main.innerHTML = `<div class="page">
    <div class="page-head"><div><h1>Boshqaruv</h1>
      <p class="muted">${owner ? "Siz platforma egasisiz: adminlarni tayinlash va chiqarish faqat sizda." : "Admin: foydalanuvchilar, buyurtmalar va murojaatlarni boshqarasiz. Adminlar va egaga nisbatan amal bajarib bo'lmaydi."}</p></div></div>
    <div class="seg" id="tabs" style="margin-bottom:18px">
      <button data-t="stats" class="on">Statistika</button><button data-t="users">Foydalanuvchilar</button>
      <button data-t="orders">Buyurtmalar</button><button data-t="reports">Murojaatlar</button>
      <button data-t="revenue">Daromad</button>
      <button data-t="broadcast">Ommaviy xabar</button><button data-t="logs">Jurnal</button>
    </div>
    <div id="pane"></div></div>`;
  const pane = $('#pane', main);
  const show = () => ({ stats, users, orders, reports, revenue, broadcast, logs })[tab](pane, owner);
  $$('#tabs button', main).forEach(b => b.addEventListener('click', () => {
    tab = b.dataset.t;
    $$('#tabs button', main).forEach(x => x.classList.toggle('on', x === b));
    show();
  }));
  show();
}

async function stats(pane) {
  pane.innerHTML = skeletons(2);
  const s = await get('/admin/stats');
  const k = (v, l) => `<div class="kpi"><b>${esc(v)}</b><span>${esc(l)}</span></div>`;
  pane.innerHTML = `<div class="stack">
    <div class="kpis">
      ${k(num(s.usersTotal), 'Jami foydalanuvchilar')}${k(num(s.drivers), 'Faol haydovchilar')}
      ${k(num(s.ordersToday), 'Bugungi buyurtmalar')}${k(num(s.ordersByStatus.NEW), 'Haydovchi kutayotgan')}
      ${k(num(s.ordersByStatus.IN_TRANSIT), "Hozir yo'lda")}${k(num(s.ordersByStatus.DELIVERED), 'Yetkazilgan')}
      ${k(money(s.deliveredValue), 'Yetkazilgan yuklar qiymati')}${k(num(s.openReports), 'Ochiq murojaatlar')}
    </div>
    <div class="kpis">
      ${k(num(s.usersActive), 'Faol hisoblar')}${k(num(s.usersBlocked), 'Bloklangan')}${k(num(s.usersRemoved), 'Chiqarilgan')}${k(num(s.admins), 'Adminlar')}
    </div></div>`;
}

// ---------------- Daromad: komissiya, shoshilinch narxi, balanslar ----------------
async function revenue(pane, owner) {
  pane.innerHTML = skeletons(2);
  let s;
  try { s = await get('/admin/monetization'); } catch (e) { pane.innerHTML = emptyState("Yuklab bo'lmadi", e.message); return; }
  const st = s.settings, can = s.canEdit;
  const dis = can ? '' : 'disabled';
  const k = (v, l) => `<div class="kpi"><b>${esc(v)}</b><span>${esc(l)}</span></div>`;
  pane.innerHTML = `<div class="stack">
    <div class="kpis">${k(money(s.revenueToday), 'Bugungi daromad')}${k(money(s.revenueMonth), 'Shu oy')}${k(money(s.revenueTotal), 'Jami daromad')}${k(money(s.walletsTotal), 'Foydalanuvchilar balanslari')}</div>
    <div class="grid-2">
      <section class="panel stack">
        <h2>Daromad sozlamalari</h2>
        <form id="mon-form" class="stack" novalidate>
          <label class="setting"><span><b>Daromad tizimi ${st.enabled ? 'yoqilgan' : "o'chiq"}</b><br><span class="small muted">O'chiq bo'lsa hech kimdan pul yechilmaydi va narxlar ko'rsatilmaydi</span></span>
            <span class="switch"><input type="checkbox" name="enabled" ${st.enabled ? 'checked' : ''} ${dis}><span></span></span></label>
          <div class="form-grid">
            <label class="field"><span>Komissiya, %</span><input class="input" name="commissionPercent" type="number" min="0" max="50" step="0.5" value="${esc(st.commissionPercent)}" ${dis}></label>
            <label class="field"><span>Eng kam komissiya, so'm</span><input class="input" name="commissionMin" type="number" min="0" step="500" value="${esc(st.commissionMin)}" ${dis}></label>
            <label class="field"><span>Eng ko'p komissiya, so'm (0 — cheklovsiz)</span><input class="input" name="commissionMax" type="number" min="0" step="500" value="${esc(st.commissionMax)}" ${dis}></label>
            <label class="field"><span>Shoshilinch e'lon narxi, so'm (0 — bepul)</span><input class="input" name="urgentFee" type="number" min="0" step="500" value="${esc(st.urgentFee)}" ${dis}></label>
            <label class="field full"><span>Balansni to'ldirish yo'riqnomasi (foydalanuvchilar profilida ko'rinadi)</span>
              <textarea class="textarea" name="topupInfo" maxlength="1000" placeholder="Masalan: Balansni to'ldirish uchun Telegramda @admin ga yozing" ${dis}>${esc(st.topupInfo || '')}</textarea></label>
          </div>
          <p class="tiny muted">Komissiya haydovchiga buyurtma berilganda yechiladi: u «Olish» tugmasini bosganda yoki yuboruvchi uning taklifini tanlaganda.
            Yuboruvchi bekor qilsa yoki admin yuk olinmasdan oldin bekor qilsa — qaytariladi, haydovchi o'zi voz kechsa — qaytarilmaydi.
            Taklif yuborish uchun haydovchi balansida komissiyaga yetadigan mablag' bo'lishi kerak.</p>
          ${can ? `<div><button class="btn btn-primary" type="submit">Saqlash</button></div>` : `<p class="small muted">Sozlamalarni faqat ega o'zgartira oladi.</p>`}
          ${s.updatedBy ? `<p class="tiny muted">Oxirgi o'zgartirish: ${esc(s.updatedBy)}, ${esc(dateTime(s.updatedAt))}</p>` : ''}
        </form>
      </section>
      <section class="panel stack">
        <h2>Balansni to'ldirish</h2>
        ${can ? `<form id="adj-form" class="stack-sm" novalidate>
            <label class="field"><span>Foydalanuvchi telefoni</span><input class="input" name="phone" type="tel" inputmode="tel" placeholder="+998 90 123 45 67" required></label>
            <label class="field"><span>Summa, so'm (yechish uchun minus bilan, masalan -5000)</span><input class="input" name="amount" type="number" step="500" required></label>
            <label class="field"><span>Izoh</span><input class="input" name="note" maxlength="300" placeholder="Masalan: kartaga o'tkazma, 20-oktabr"></label>
            <div><button class="btn btn-primary" type="submit">Bajarish</button></div></form>
          <p class="tiny muted">Pul sizga kelganini tekshirib, keyin balansga qo'shing. Har bir amal jurnalga yoziladi va foydalanuvchiga xabar boradi.</p>`
          : `<p class="small muted">Balanslarni faqat ega o'zgartira oladi.</p>`}
      </section>
    </div>
    <section class="panel"><h2>So'nggi harakatlar</h2><div id="tx-list" style="margin-top:12px">${skeletons(2)}</div></section>
  </div>`;

  const mf = $('#mon-form', pane);
  if (can) mf.addEventListener('submit', async (e) => {
    e.preventDefault();
    clearError(mf);
    const body = {
      enabled: mf.enabled.checked,
      commissionPercent: Number(mf.commissionPercent.value || 0),
      commissionMin: Math.round(Number(mf.commissionMin.value || 0)),
      commissionMax: Math.round(Number(mf.commissionMax.value || 0)),
      urgentFee: Math.round(Number(mf.urgentFee.value || 0)),
      topupInfo: mf.topupInfo.value.trim() || null,
    };
    if (body.enabled && !st.enabled) {
      const ok = await confirmDialog('Daromad tizimini yoqasizmi?',
        "Haydovchilardan komissiya va shoshilinch e'lon uchun to'lov olina boshlaydi. Balansi yetmagan haydovchilar taklif yubora olmaydi — avval ularni ogohlantiring.",
        { okText: 'Yoqish' });
      if (!ok) return;
    }
    try {
      await busy(mf.querySelector('[type=submit]'), () => put('/admin/monetization', body));
      toast('Saqlandi. Foydalanuvchilar yangi narxlarni sahifani yangilaganda ko\'radi', 'ok');
      revenue(pane, owner);
    } catch (err) { showError(mf, err); }
  });

  const af = $('#adj-form', pane);
  if (af) af.addEventListener('submit', async (e) => {
    e.preventDefault();
    clearError(af);
    const phone = af.phone.value.trim(), amount = Math.round(Number(af.amount.value)), note = af.note.value.trim() || null;
    if (!phone || !amount) return showError(af, { message: 'Telefon raqam va summani kiriting' });
    const ok = await confirmDialog(amount > 0 ? "Balansni to'ldirasizmi?" : 'Balansdan yechasizmi?',
      `${phone}: ${amount > 0 ? '+' : '−'}${money(Math.abs(amount))}`, { okText: 'Bajarish', danger: amount < 0 });
    if (!ok) return;
    try {
      const r = await busy(af.querySelector('[type=submit]'), () => post('/admin/wallet/adjust', { phone, amount, note }));
      toast(`${r.userName}: balans ${money(r.balance)}`, 'ok');
      af.reset();
      loadTx();
    } catch (err) { showError(af, err); }
  });

  async function loadTx() {
    const box = $('#tx-list', pane);
    try {
      const data = await get('/admin/wallet/transactions');
      box.innerHTML = data.items.length ? `<div class="table-wrap"><table>
        <thead><tr><th>Vaqt</th><th>Foydalanuvchi</th><th>Turi</th><th>Summa</th><th>Qoldiq</th><th>Izoh</th></tr></thead>
        <tbody>${data.items.map(t => `<tr>
          <td class="small">${esc(dateTime(t.createdAt))}</td>
          <td class="small"><a href="#/u/${t.userId}">${esc(t.userName || '#' + t.userId)}</a>${t.userPhone ? `<br><span class="muted">${esc(phoneFmt(t.userPhone))}</span>` : ''}</td>
          <td class="small">${esc(t.typeLabel)}${t.orderId ? ` <a href="#/orders/${t.orderId}">#${t.orderId}</a>` : ''}</td>
          <td class="num tx-amt ${t.amount < 0 ? 'amt-neg' : 'amt-pos'}">${t.amount > 0 ? '+' : '−'}${esc(money(Math.abs(t.amount)))}</td>
          <td class="num small">${esc(money(t.balanceAfter))}</td>
          <td class="small">${esc(t.note || '')}${t.actorName ? `<br><span class="tiny muted">${esc(t.actorName)}</span>` : ''}</td></tr>`).join('')}</tbody></table></div>`
        : `<p class="muted small">Hali harakat yo'q.</p>`;
    } catch (err) { box.innerHTML = `<p class="muted small">${esc(err.message)}</p>`; }
  }
  loadTx();
}

async function users(pane, owner) {
  pane.innerHTML = `<form class="filters" id="uf" style="margin:0 0 14px">
      <input class="input" name="q" type="search" placeholder="Ism, familiya yoki telefon" aria-label="Qidiruv">
      <select class="select" name="role" aria-label="Rol">${options(ROLE, '', 'Barcha rollar')}</select>
      <select class="select" name="status" aria-label="Holat">${options(USTATUS, '', 'Barcha holatlar')}</select>
    </form><div id="ul">${skeletons()}</div>`;
  const f = $('#uf', pane);
  const load = async () => {
    const q = Object.fromEntries(new FormData(f));
    const data = await get('/admin/users', q);
    $('#ul', pane).innerHTML = data.items.length ? `<div class="table-wrap"><table>
      <thead><tr><th>Foydalanuvchi</th><th>Rol / holat</th><th>Statistika</th><th>Ro'yxatdan o'tgan</th><th>Amallar</th></tr></thead>
      <tbody>${data.items.map(u => `<tr>
        <td><a class="person" href="#/u/${u.id}">${avatar(u.avatarUrl, u.fullName)}<div><div class="person-name">${esc(u.fullName)}</div>
          <div class="person-sub">${esc(phoneFmt(u.phone))}${u.region ? ', ' + esc(u.region) : ''}${u.telegramLinked ? ', Telegram ✓' : ''}</div></div></a></td>
        <td><div class="row" style="gap:6px"><span class="pill plain role-${u.role}">${esc(ROLE[u.role])}</span><span class="pill us-${u.status}">${esc(USTATUS[u.status])}</span></div>
          ${u.blockReason ? `<div class="tiny muted" style="margin-top:4px">${esc(u.blockReason)}</div>` : ''}</td>
        <td class="small">⭐ ${u.rating.toFixed(1)} (${u.ratingCount})<br>${u.ordersCreated} buyurtma, ${u.deliveriesCompleted} yetkazish${u.cancelledAsDriver ? `<br><span style="color:var(--danger)">${u.cancelledAsDriver} marta voz kechgan</span>` : ''}</td>
        <td class="small">${esc(dateTime(u.createdAt))}<br><span class="muted">kirgan: ${esc(ago(u.lastLoginAt) || '—')}</span></td>
        <td><div class="row" style="gap:6px">${actionsFor(u, owner)}</div></td></tr>`).join('')}</tbody></table></div>`
      : emptyState('Foydalanuvchi topilmadi', "Qidiruvni o'zgartiring.");
    $$('[data-act]', pane).forEach(b => b.addEventListener('click', () => act(b.dataset.act, Number(b.dataset.id), b.dataset.name, load)));
  };
  $$('[name]', f).forEach(el => el.addEventListener('change', load));
  f.addEventListener('submit', (e) => { e.preventDefault(); load(); });
  load();
}

function actionsFor(u, owner) {
  if (!u.canManage) return `<span class="tiny muted">${u.role === 'OWNER' ? 'Ega' : u.role === 'ADMIN' ? 'Faqat ega boshqaradi' : '—'}</span>`;
  const b = (act, label, cls = '') => `<button class="btn btn-sm ${cls}" data-act="${act}" data-id="${u.id}" data-name="${esc(u.fullName)}">${label}</button>`;
  let h = '';
  if (u.status === 'ACTIVE') h += b('block', 'Bloklash', 'btn-danger');
  if (u.status === 'BLOCKED') h += b('unblock', 'Blokdan chiqarish');
  if (u.status !== 'REMOVED') h += b('remove', 'Chiqarib yuborish', 'btn-danger');
  if (u.status === 'REMOVED') h += b('restore', 'Qayta tiklash');
  if (owner && u.canChangeRole && u.status === 'ACTIVE') {
    h += u.role === 'ADMIN' ? b('revoke', 'Adminlikdan olish') : b('make-admin', 'Admin qilish', 'btn-route');
    h += b('transfer', 'Egalikni topshirish', 'btn-ghost');
  }
  return h;
}

async function act(action, id, name, reload) {
  const cfg = {
    block: ['Bloklash', `${name} tizimga kira olmaydi. Keyin blokdan chiqarish mumkin.`, true, true],
    unblock: ['Blokdan chiqarish', `${name} yana tizimdan foydalana oladi.`, false, false],
    remove: ['Chiqarib yuborish', `${name} hisobi tizimdan chiqariladi, ochiq buyurtmalari bekor qilinadi.`, true, true],
    restore: ['Qayta tiklash', `${name} hisobi qayta faollashtiriladi.`, false, false],
    'make-admin': ['Admin qilish', `${name} foydalanuvchilarni bloklash, buyurtma va murojaatlarni boshqarish huquqini oladi.`, false, false],
    revoke: ['Adminlikdan olish', `${name} oddiy foydalanuvchiga aylanadi.`, true, false],
    transfer: ['Egalikni topshirish', `${name} platforma egasi bo'ladi, siz esa admin bo'lib qolasiz. Bu amalni faqat yangi ega qaytara oladi.`, true, false],
  }[action];
  const r = await confirmDialog(cfg[0], cfg[1], { danger: cfg[2], okText: cfg[0], withReason: cfg[3] });
  if (!r) return;
  const urls = {
    block: [`/admin/users/${id}/block`, { reason: r.reason }], unblock: [`/admin/users/${id}/unblock`],
    remove: [`/admin/users/${id}/remove`, { reason: r.reason }], restore: [`/admin/users/${id}/restore`],
    'make-admin': [`/admin/users/${id}/role`, { role: 'ADMIN' }], revoke: [`/admin/users/${id}/role`, { role: 'USER' }],
    transfer: [`/admin/users/${id}/transfer-ownership`],
  };
  try {
    await post(...urls[action]);
    toast('Bajarildi', 'ok');
    if (action === 'transfer') { location.hash = '#/'; location.reload(); return; }
    reload();
  } catch (e) { toast(e.message, 'err'); }
}

async function orders(pane) {
  pane.innerHTML = `<form class="filters" id="of" style="margin:0 0 14px">
      <input class="input" name="q" type="search" placeholder="Yuk nomi yoki yuboruvchi telefoni" aria-label="Qidiruv">
      <select class="select" name="status" aria-label="Holat">${options(STATUS, '', 'Barcha holatlar')}</select></form>
    <div id="ol">${skeletons()}</div>`;
  const f = $('#of', pane);
  const load = async () => {
    const data = await get('/admin/orders', Object.fromEntries(new FormData(f)));
    $('#ol', pane).innerHTML = data.items.length ? `<div class="table-wrap"><table>
      <thead><tr><th>#</th><th>Yuk</th><th>Marshrut</th><th>Yuboruvchi / haydovchi</th><th>Narx</th><th>Holat</th><th></th></tr></thead>
      <tbody>${data.items.map(o => `<tr>
        <td class="num"><a href="#/orders/${o.id}">${o.id}</a></td>
        <td><b>${esc(o.title)}</b><div class="tiny muted">${esc(dateTime(o.createdAt))}</div></td>
        <td class="small">${esc(o.from.regionName)} → ${esc(o.to.regionName)}</td>
        <td class="small"><a href="#/u/${o.sender.id}">${esc(o.sender.fullName)}</a>${o.driver ? `<br><a href="#/u/${o.driver.id}">${esc(o.driver.fullName)}</a>` : ''}</td>
        <td class="num small">${esc(money(o.finalPrice ?? o.price))}</td>
        <td>${statusPill(o.status)}</td>
        <td>${['DELIVERED', 'CANCELLED'].includes(o.status) ? '' : `<button class="btn btn-danger btn-sm" data-cancel="${o.id}">Bekor qilish</button>`}</td></tr>`).join('')}</tbody></table></div>`
      : emptyState('Buyurtma topilmadi', '');
    $$('[data-cancel]', pane).forEach(b => b.addEventListener('click', async () => {
      const r = await confirmDialog(`#${b.dataset.cancel} buyurtmani bekor qilish`, 'Yuboruvchi va haydovchiga xabar boradi.', { danger: true, withReason: true });
      if (!r) return;
      try { await post(`/admin/orders/${b.dataset.cancel}/cancel`, { reason: r.reason }); toast('Bekor qilindi'); load(); } catch (e) { toast(e.message, 'err'); }
    }));
  };
  $$('[name]', f).forEach(el => el.addEventListener('change', load));
  f.addEventListener('submit', (e) => { e.preventDefault(); load(); });
  load();
}

async function reports(pane) {
  pane.innerHTML = `<div class="seg" id="rs" style="margin-bottom:14px"><button data-s="OPEN" class="on">Ochiq</button><button data-s="RESOLVED">Hal qilingan</button><button data-s="REJECTED">Rad etilgan</button><button data-s="">Hammasi</button></div><div id="rl">${skeletons()}</div>`;
  let status = 'OPEN';
  const load = async () => {
    const data = await get('/admin/reports', { status });
    $('#rl', pane).innerHTML = data.items.length ? `<div class="stack-sm">${data.items.map(r => `<div class="panel">
        <div class="row-between"><div class="small"><a href="#/u/${r.authorId}"><b>${esc(r.authorName)}</b></a>
          ${r.targetId ? ` shikoyat qildi: <a href="#/u/${r.targetId}"><b>${esc(r.targetName)}</b></a>` : ' murojaat qildi'}
          ${r.orderId ? `, buyurtma <a href="#/orders/${r.orderId}">#${r.orderId}</a>` : ''}</div>
          <span class="small muted">${esc(ago(r.createdAt))}</span></div>
        <p style="margin-top:8px;white-space:pre-line">${esc(r.text)}</p>
        ${r.resolution ? `<p class="small muted" style="margin-top:6px">Javob: ${esc(r.resolution)}</p>` : ''}
        ${r.status === 'OPEN' ? `<form class="row" data-id="${r.id}" style="margin-top:10px">
            <input class="input" name="resolution" placeholder="Javob (foydalanuvchiga yuboriladi)" style="flex:1;min-width:200px">
            <button class="btn btn-ok btn-sm" name="act" value="RESOLVED">Hal qilindi</button>
            <button class="btn btn-sm" name="act" value="REJECTED">Rad etish</button></form>` : ''}
      </div>`).join('')}</div>` : emptyState("Murojaat yo'q", '');
    $$('form[data-id]', pane).forEach(fm => fm.addEventListener('submit', async (e) => {
      e.preventDefault();
      try {
        await post(`/admin/reports/${fm.dataset.id}/resolve`, { status: e.submitter?.value || 'RESOLVED', resolution: fm.resolution.value.trim() || null });
        toast('Saqlandi', 'ok');
        load();
      } catch (err) { toast(err.message, 'err'); }
    }));
  };
  $$('#rs button', pane).forEach(b => b.addEventListener('click', () => {
    status = b.dataset.s;
    $$('#rs button', pane).forEach(x => x.classList.toggle('on', x === b));
    load();
  }));
  load();
}

function broadcast(pane) {
  pane.innerHTML = `<section class="panel stack" style="max-width:640px">
    <h2>Ommaviy xabar</h2>
    <p class="small muted">Xabar Telegram botga ulangan barcha faol foydalanuvchilarga yuboriladi.</p>
    <form class="stack" novalidate>
      <textarea class="textarea" name="text" maxlength="3000" style="min-height:160px" placeholder="Masalan: Ertadan Toshkent — Samarqand yo'lida ta'mirlash ishlari boshlanadi…" required></textarea>
      <button class="btn btn-primary" type="submit">Yuborish</button></form>
    <p class="small muted" id="bc-media">📷 <b>Rasm, video yoki dumaloq video</b> yubormoqchimisiz? Uni o'z Telegramingizdan
      botga yuboring — bot «Hammaga yuborilsinmi?» deb so'raydi, tasdiqlasangiz hamma foydalanuvchiga boradi.
      Albom, ovozli xabar va fayl ham bo'ladi.</p></section>`;
  config().then((c) => {
    const el = $('#bc-media', pane);
    if (el && c.botUsername) {
      el.insertAdjacentHTML('beforeend', ` <a href="https://t.me/${encodeURIComponent(c.botUsername)}" target="_blank" rel="noopener">@${esc(c.botUsername)} ni ochish</a>`);
    }
  });
  const form = $('form', pane);
  form.addEventListener('submit', async (e) => {
    e.preventDefault();
    clearError(form);
    const r = await confirmDialog('Xabarni hammaga yuborasizmi?', 'Yuborilgan xabarni qaytarib bo\'lmaydi.', { okText: 'Yuborish' });
    if (!r) return;
    try {
      const res = await busy(form.querySelector('[type=submit]'), () => post('/admin/broadcast', { text: form.text.value.trim() }));
      toast(res.telegramEnabled ? `${res.recipients} ta foydalanuvchiga yuborilmoqda` : "Telegram bot ulanmagan — xabar yuborilmadi", res.telegramEnabled ? 'ok' : 'err');
      form.reset();
    } catch (err) { showError(form, err); }
  });
}

async function logs(pane) {
  pane.innerHTML = skeletons();
  const data = await get('/admin/logs');
  const labels = {
    BLOCK: 'blokladi', UNBLOCK: 'blokdan chiqardi', REMOVE: 'chiqarib yubordi', RESTORE: 'qayta tikladi',
    MAKE_ADMIN: 'admin qildi', REVOKE_ADMIN: 'adminlikdan oldi', TRANSFER_OWNERSHIP: 'egalikni topshirdi',
    CANCEL_ORDER: 'buyurtmani bekor qildi', DELETE_REVIEW: "sharhni o'chirdi", BROADCAST: 'ommaviy xabar yubordi',
    MONETIZATION_ON: 'daromad tizimini yoqdi', MONETIZATION_OFF: "daromad tizimini o'chirdi",
    MONETIZATION_UPDATE: "narxlarni o'zgartirdi", BALANCE_TOPUP: "balansni to'ldirdi", BALANCE_DEBIT: 'balansdan yechdi',
  };
  pane.innerHTML = data.items.length ? `<div class="table-wrap"><table><thead><tr><th>Vaqt</th><th>Kim</th><th>Amal</th><th>Kimga</th><th>Tafsilot</th></tr></thead>
    <tbody>${data.items.map(l => `<tr><td class="small">${esc(dateTime(l.createdAt))}</td><td class="small">${esc(l.actorName)}</td>
      <td><b>${esc(labels[l.action] || l.action)}</b></td>
      <td class="small">${l.targetUserId ? `<a href="#/u/${l.targetUserId}">${esc(l.targetName)}</a>` : ''}</td>
      <td class="small muted">${esc(l.details || '')}</td></tr>`).join('')}</tbody></table></div>` : emptyState("Jurnal bo'sh", '');
  void del;
}
