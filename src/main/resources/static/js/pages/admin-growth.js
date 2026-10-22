// Boshqaruv: Telegram kanallar (avtomatik e'lonlar), manbalar (qaysi havoladan kim keldi) va haydovchi hujjatlarini tekshirish
import { auth, get, post, put, del, api } from '../api.js';
import {
  $, $$, esc, icon, avatar, toast, modal, confirmDialog, busy, showError, clearError, dateTime, ago, num, phoneFmt,
  regionOptions, emptyState, skeletons, copyText,
} from '../ui.js';

// ---- QR kod (vendor/qrcode.js — MIT, kerak bo'lganda yuklanadi) ----
let qrLib = null;
function loadQr() {
  return qrLib ??= new Promise((resolve, reject) => {
    if (window.qrcode) return resolve(window.qrcode);
    const s = document.createElement('script');
    s.src = '/js/vendor/qrcode.js';
    s.onload = () => (window.qrcode ? resolve(window.qrcode) : reject(new Error('QR')));
    s.onerror = () => { qrLib = null; reject(new Error("QR kutubxonasini yuklab bo'lmadi")); };
    document.head.appendChild(s);
  });
}

export async function qrSvg(text) {
  const qrcode = await loadQr();
  const qr = qrcode(0, 'M');
  qr.addData(text);
  qr.make();
  return qr.createSvgTag({ cellSize: 6, margin: 4, scalable: true, alt: text });
}

function copyButtons(el) {
  $$('[data-copy]', el).forEach(b => b.addEventListener('click', async () => {
    toast(await copyText(b.dataset.copy) ? 'Havola nusxalandi' : "Nusxalab bo'lmadi", 'ok');
  }));
}

async function qrModal(title, link, extraHtml = '') {
  let svg = '';
  try { svg = await qrSvg(link); } catch (e) { toast(e.message, 'err'); }
  modal(title, `<div class="stack">
      <div class="qr-box">${svg}</div>
      <div class="link-box"><code>${esc(link)}</code><button class="btn btn-sm" type="button" data-copy="${esc(link)}">${icon('copy')}Nusxa</button></div>
      ${extraHtml}
      <p class="tiny muted">QR rasmni saqlash uchun ustiga bosib turing (telefonda) yoki skrinshot oling. Chop etiladigan varaqa uchun «Varaqa» tugmasidan foydalaning.</p>
    </div>`, { onMount: ({ el }) => copyButtons(el) });
}

// =====================================================================
// Kanallar
// =====================================================================
export async function channelsTab(pane) {
  pane.innerHTML = skeletons(2);
  let d;
  try { d = await get('/admin/channels'); } catch (e) { pane.innerHTML = emptyState("Yuklab bo'lmadi", e.message); return; }
  const bot = d.botUsername ? '@' + d.botUsername : 'bot';
  pane.innerHTML = `<div class="stack">
    ${!d.botEnabled ? `<div class="ver-state bad">${icon('close')}<div><b>Telegram bot ulanmagan</b><br>TELEGRAM_BOT_TOKEN berilmaguncha kanallarga e'lon chiqmaydi.</div></div>` : ''}
    <section class="panel stack">
      <h2>Telegram kanal va guruhlar</h2>
      <p class="small muted">Yangi ochiq buyurtmalar shu kanallarga avtomatik chiqadi — yuk, yo'nalish, sana, narx; yuk egasining ismi va telefoni
        <b>chiqmaydi</b>. Haydovchi topilganda post «✅ Haydovchi topildi», bekor qilinganda «❌ E'lon yopilgan» ga o'zgaradi.
        Postdagi «📩 Taklif berish» tugmasi buyurtmani YukGo'da ochadi, kim shu kanaldan kelgani «Manbalar» da ko'rinadi.</p>
      <form id="feed-form" class="filters" novalidate style="flex-wrap:wrap">
        <input class="input" name="chat" placeholder="@kanal_nomi yoki https://t.me/kanal_nomi" style="flex:2;min-width:220px" required>
        <select class="select" name="regionId" aria-label="Viloyat filtri" style="flex:1;min-width:180px">${await regionOptions('', 'Barcha viloyatlar')}</select>
        <button class="btn btn-primary" type="submit">${icon('plus')}Qo'shish</button>
      </form>
      <details class="small"><summary><b>Kanalni qanday ulash?</b></summary>
        <ol style="margin:8px 0 0;padding-left:20px" class="stack-sm">
          <li>Kanal (yoki guruh) sozlamalari → <b>Administratorlar</b> → <b>Admin qo'shish</b> → <b>${esc(bot)}</b> ni toping.</li>
          <li>Kanalda unga <b>«Xabar joylash»</b> (Post messages) huquqini bering. Guruhda oddiy a'zo bo'lishi yetarli.</li>
          <li>Kanal manzilini (<code>@kanal_nomi</code>) shu yerga yozib «Qo'shish» ni bosing. Yopiq kanal bo'lsa — uning ID sini kiriting (<code>-100</code> bilan boshlanadi).</li>
          <li>Viloyat tanlasangiz — kanalga faqat shu viloyatdan yoki shu viloyatga boradigan yuklar chiqadi (masalan, «Samarqand yuk» kanali uchun).</li>
        </ol>
        <p class="tiny muted" style="margin-top:8px">Kanal egasi bilan kelishib ulang: begona kanalga e'lon tashlash ularning qoidasiga zid bo'lishi mumkin.
          Hamkor kanallarga taklif matni — paketdagi <code>marketing/hamkor-kanallarga-taklif.md</code>.</p>
      </details>
      <div id="feeds">${feedsHtml(d.feeds)}</div>
    </section>
    <section class="panel stack-sm">
      <h2>«Taklif berish» tugmasi qanday ochilsin?</h2>
      <label class="setting"><span><b>To'g'ridan-to'g'ri ilova (Main Mini App)</b><br><span class="small muted">Yoqilsa — tugma YukGo'ni Telegram ichida bir bosishda ochadi.
        O'chiq bo'lsa — avval bot ochiladi va buyurtma kartasini «Taklif berish» tugmasi bilan yuboradi (ikki bosish).</span></span>
        <span class="switch"><input type="checkbox" id="mma" ${d.mainMiniApp ? 'checked' : ''}><span></span></span></label>
      <p class="tiny muted">Yoqishdan oldin <b>@BotFather</b> da: <code>/mybots</code> → ${esc(bot)} → <b>Bot Settings</b> → <b>Configure Mini App</b> →
        <b>Enable Mini App</b> → sayt manzilini (<code>${esc(location.origin)}</code>) yuboring. BotFather'da yoqilmagan bo'lsa tugma ishlamaydi —
        bu holda o'chiq qoldiring.</p>
    </section>
  </div>`;

  const ff = $('#feed-form', pane);
  ff.addEventListener('submit', async (e) => {
    e.preventDefault();
    clearError(ff);
    const chat = ff.chat.value.trim();
    if (!chat) return showError(ff, { message: 'Kanal manzilini kiriting' });
    try {
      await busy(ff.querySelector('[type=submit]'), () => post('/admin/channels', { chat, regionId: Number(ff.regionId.value) || null }));
      toast("Kanal qo'shildi — yangi buyurtmalar endi u yerga ham chiqadi", 'ok');
      channelsTab(pane);
    } catch (err) { showError(ff, err); }
  });
  $('#mma', pane).addEventListener('change', async (e) => {
    try {
      await put('/admin/channels/settings', { mainMiniApp: e.target.checked });
      toast(e.target.checked ? 'Tugma endi ilovani to\'g\'ridan-to\'g\'ri ochadi' : 'Tugma avval botni ochadi', 'ok');
    } catch (err) { e.target.checked = !e.target.checked; toast(err.message, 'err'); }
  });
  bindFeeds(pane);
}

function feedsHtml(feeds) {
  if (!feeds.length) return `<p class="muted small">Hali kanal ulanmagan.</p>`;
  return feeds.map(f => `<div class="feed-row" data-id="${f.id}">
      <div style="min-width:0">
        <b>${esc(f.title || f.username || String(f.chatId))}</b>
        ${f.username ? ` <a class="small" href="https://t.me/${esc(f.username)}" target="_blank" rel="noopener">@${esc(f.username)}</a>` : ''}
        ${f.active ? '' : ' <span class="pill plain">to\'xtatilgan</span>'}
        <div class="tiny muted">${f.chatType === 'channel' ? 'Kanal' : 'Guruh'} · ${esc(f.regionName || 'barcha viloyatlar')} ·
          ${num(f.postsCount)} ta e'lon · <b>${num(f.signups)}</b> kishi shu yerdan ro'yxatdan o'tdi · manba: <code>${esc(f.sourceCode)}</code></div>
      </div>
      <div class="actions">
        <button class="btn btn-sm" data-region="${f.id}" data-current="${f.regionId ?? ''}">Viloyat</button>
        <button class="btn btn-sm" data-toggle="${f.id}" data-active="${f.active}">${f.active ? "To'xtatish" : 'Yoqish'}</button>
        <button class="btn btn-ghost btn-sm" data-remove="${f.id}" data-name="${esc(f.title || f.username || '')}">O'chirish</button>
      </div>
    </div>`).join('');
}

function bindFeeds(pane) {
  $$('[data-toggle]', pane).forEach(b => b.addEventListener('click', async () => {
    try {
      await put('/admin/channels/' + b.dataset.toggle, { active: b.dataset.active !== 'true' });
      channelsTab(pane);
    } catch (e) { toast(e.message, 'err'); }
  }));
  $$('[data-remove]', pane).forEach(b => b.addEventListener('click', async () => {
    const ok = await confirmDialog(`«${b.dataset.name}» o'chirilsinmi?`,
      "Yangi e'lonlar u yerga chiqmaydi. Kanaldagi eski postlar qoladi (ularni kanalning o'zida o'chirish mumkin).",
      { danger: true, okText: "O'chirish" });
    if (!ok) return;
    try { await del('/admin/channels/' + b.dataset.remove); channelsTab(pane); } catch (e) { toast(e.message, 'err'); }
  }));
  $$('[data-region]', pane).forEach(b => b.addEventListener('click', async () => {
    modal('Qaysi yuklar chiqsin?', `<form class="stack" novalidate>
        <select class="select" name="regionId">${await regionOptions(b.dataset.current, 'Barcha viloyatlar')}</select>
        <p class="small muted">Viloyat tanlansa — faqat shu viloyatdan yoki shu viloyatga boradigan yuklar chiqadi.</p>
        <button class="btn btn-primary" type="submit">Saqlash</button></form>`, {
      onMount: ({ el, close }) => {
        const f = $('form', el);
        f.addEventListener('submit', async (e) => {
          e.preventDefault();
          const regionId = Number(f.regionId.value) || null;
          try {
            await put('/admin/channels/' + b.dataset.region, regionId ? { regionId } : { allRegions: true });
            close();
            channelsTab(pane);
          } catch (err) { showError(f, err); }
        });
      },
    });
  }));
}

// =====================================================================
// Manbalar: qaysi kanal, hamkor, varaqa yoki dala xodimi havolasidan kim keldi
// =====================================================================
export async function sourcesTab(pane, _owner, days = 30) {
  pane.innerHTML = skeletons(2);
  let r;
  try { r = await get('/admin/sources', { days }); } catch (e) { pane.innerHTML = emptyState("Yuklab bo'lmadi", e.message); return; }
  const k = (v, l) => `<div class="kpi"><b>${esc(v)}</b><span>${esc(l)}</span></div>`;
  const share = r.totalUsers ? Math.round(r.attributedUsers * 100 / r.totalUsers) : 0;
  pane.innerHTML = `<div class="stack">
    <div class="seg" id="period">${[[7, '7 kun'], [30, '30 kun'], [90, '90 kun'], [0, 'Hammasi']].map(([v, l]) =>
      `<button data-d="${v}" class="${v === days ? 'on' : ''}">${l}</button>`).join('')}</div>
    <div class="kpis">${k(num(r.totalUsers), "Ro'yxatdan o'tganlar")}${k(num(r.attributedUsers), 'Havola orqali kelganlar')}${k(share + '%', 'Havola ulushi')}</div>
    <section class="panel stack">
      <h2>Manbalar</h2>
      <p class="small muted">Har bir kanal, hamkor, varaqa yoki dala xodimiga alohida kod bering — kim qancha odam olib kelgani,
        ulardan nechtasi haydovchi, nechtasi yuk egasi va nechtasi faollashgani shu yerda ko'rinadi.
        <b>Faol</b> — kamida bitta buyurtmasi yakunlanganlar (haydovchi sifatida yetkazgan yoki yuk egasi sifatida yuki yetkazilgan).
        Agent va hamkorga pulni «Keldi» uchun emas, <b>«Faol»</b> uchun to'lang.</p>
      <form id="src-form" class="filters" novalidate style="flex-wrap:wrap">
        <input class="input" name="code" placeholder="kod: agent_ali" pattern="[a-z0-9_-]{2,40}" style="flex:1;min-width:150px" required>
        <input class="input" name="name" placeholder="Nomi: Ali (Chorsu bozori)" maxlength="120" style="flex:2;min-width:200px" required>
        <input class="input" name="note" placeholder="Izoh (ixtiyoriy)" maxlength="300" style="flex:2;min-width:180px">
        <button class="btn btn-primary" type="submit">${icon('plus')}Kod yaratish</button>
      </form>
      ${r.sources.length ? `<div>
        <div class="src-row head"><div>Manba</div><div class="n">Keldi</div><div class="n">Haydovchi</div><div class="n">Yuk egasi</div><div class="n" title="Kamida bitta buyurtmasi yakunlanganlar">Faol</div><div></div></div>
        ${r.sources.map(s => `<div class="src-row">
          <div style="min-width:0"><b>${esc(s.name)}</b> <code class="tiny">${esc(s.code)}</code>
            <div class="tiny muted">${s.note ? esc(s.note) + ' · ' : ''}${s.lastSignupAt ? 'oxirgisi ' + esc(ago(s.lastSignupAt)) : "hali hech kim kelmadi"}</div></div>
          <div class="n" data-label="Keldi">${num(s.users)}</div>
          <div class="n" data-label="Haydovchi">${num(s.drivers)}</div>
          <div class="n" data-label="Yuk egasi">${num(s.senders)}</div>
          <div class="n" data-label="Faol">${num(s.activated)}</div>
          <div class="actions">
            <button class="btn btn-sm" data-qr="${esc(s.botLink)}" data-title="${esc(s.name)}" data-site="${esc(s.siteLink)}" data-code="${esc(s.code)}">${icon('qr')}QR</button>
            <a class="btn btn-sm" href="/promo.html?code=${encodeURIComponent(s.code)}&kind=driver" target="_blank" rel="noopener">Varaqa</a>
            ${s.saved ? `<button class="btn btn-ghost btn-sm" data-del="${esc(s.code)}">O'chirish</button>` : ''}
          </div></div>`).join('')}
      </div>` : `<p class="muted small">Hali manba yo'q. Birinchi kodni yarating.</p>`}
    </section>
    <section class="panel stack-sm">
      <h2>Eng ko'p taklif qilganlar</h2>
      ${r.referrers.length ? `<div class="table-wrap"><table><thead><tr><th>Foydalanuvchi</th><th>Taklif qildi</th><th>Buyurtma yakunladi</th></tr></thead>
        <tbody>${r.referrers.map(x => `<tr><td><a href="#/u/${x.userId}">${esc(x.name)}</a>${x.phone ? `<br><span class="tiny muted">${esc(phoneFmt(x.phone))}</span>` : ''}</td>
          <td class="num">${num(x.invited)}</td><td class="num">${num(x.rewarded)}</td></tr>`).join('')}</tbody></table></div>`
        : `<p class="muted small">Hali hech kim do'stini taklif qilmagan. Har bir foydalanuvchining profilida shaxsiy havolasi bor.</p>`}
    </section>
  </div>`;

  $$('#period button', pane).forEach(b => b.addEventListener('click', () => sourcesTab(pane, _owner, Number(b.dataset.d))));
  const f = $('#src-form', pane);
  f.addEventListener('submit', async (e) => {
    e.preventDefault();
    clearError(f);
    const body = { code: f.code.value.trim().toLowerCase(), name: f.name.value.trim(), note: f.note.value.trim() || null };
    if (!/^[a-z0-9_-]{2,40}$/.test(body.code)) return showError(f, { message: 'Kod: 2-40 ta kichik lotin harfi, raqam, _ yoki -' });
    if (!body.name) return showError(f, { message: 'Nomini kiriting' });
    try {
      const s = await busy(f.querySelector('[type=submit]'), () => post('/admin/sources', body));
      toast('Kod yaratildi', 'ok');
      await sourcesTab(pane, _owner, days);
      sourceQr(s.name, s.botLink, s.siteLink, s.code);
    } catch (err) { showError(f, err); }
  });
  $$('[data-qr]', pane).forEach(b => b.addEventListener('click', () => sourceQr(b.dataset.title, b.dataset.qr, b.dataset.site, b.dataset.code)));
  $$('[data-del]', pane).forEach(b => b.addEventListener('click', async () => {
    const ok = await confirmDialog(`«${b.dataset.del}» kodi o'chirilsinmi?`,
      "Havola ishlashda davom etadi va kelganlar hisobotda kod bilan qoladi — faqat nomi va izohi o'chadi.", { danger: true, okText: "O'chirish" });
    if (!ok) return;
    try { await del('/admin/sources/' + encodeURIComponent(b.dataset.del)); sourcesTab(pane, _owner, days); } catch (err) { toast(err.message, 'err'); }
  }));
}

function sourceQr(name, botLink, siteLink, code) {
  qrModal(name || code, botLink, `
    <div class="link-box"><code>${esc(siteLink)}</code><button class="btn btn-sm" type="button" data-copy="${esc(siteLink)}">${icon('copy')}Sayt</button></div>
    <div class="row">
      <a class="btn btn-sm" href="/promo.html?code=${encodeURIComponent(code)}&kind=driver" target="_blank" rel="noopener">Haydovchilar varaqasi</a>
      <a class="btn btn-sm" href="/promo.html?code=${encodeURIComponent(code)}&kind=sender" target="_blank" rel="noopener">Yuk egalari varaqasi</a>
      <a class="btn btn-sm" href="/promo.html?code=${encodeURIComponent(code)}&kind=sticker" target="_blank" rel="noopener">Stiker</a>
    </div>
    <p class="tiny muted">QR — Telegram bot havolasi: skanerlagan odam botga kiradi, bot uni YukGo'ga olib kiradi va kod saqlanadi.
      Sayt havolasi — brauzer uchun (Instagram, SMS, sayt).</p>`);
}

// =====================================================================
// Haydovchi hujjatlarini tekshirish
// =====================================================================
const urls = new Set();
function forgetUrls() {
  urls.forEach(u => URL.revokeObjectURL(u));
  urls.clear();
}

async function docUrl(id, kind) {
  const res = await fetch(`/api/admin/driver-verifications/${id}/files/${kind}`, {
    headers: auth.token ? { Authorization: 'Bearer ' + auth.token } : {},
    cache: 'no-store',
  });
  if (!res.ok) {
    let msg = "Rasmni yuklab bo'lmadi";
    try { msg = (await res.json()).message || msg; } catch { /* */ }
    throw new Error(msg);
  }
  const u = URL.createObjectURL(await res.blob());
  urls.add(u);
  return u;
}

export async function verifyTab(pane, _owner, onCount) {
  forgetUrls();
  pane.innerHTML = skeletons(2);
  let d;
  try { d = await get('/admin/driver-verifications'); } catch (e) { pane.innerHTML = emptyState("Yuklab bo'lmadi", e.message); return; }
  onCount?.(d.pending.length);
  pane.innerHTML = `<div class="stack">
    <section class="stack">
      <div><h2>Tekshiruv kutayotgan haydovchilar</h2>
        <p class="small muted">Guvohnomadagi ism profildagi ism bilan, texnik pasportdagi davlat raqami profildagi raqam bilan bir xilmi — tekshiring.
          Qaror qilingan zahoti rasmlar bazadan o'chiriladi.</p></div>
      ${d.pending.length ? d.pending.map(v => `<div class="ver-card" data-id="${v.id}">
          <div class="row-between">
            <a class="person" href="#/u/${v.userId}">${avatar(v.avatarUrl, v.fullName)}<div><div class="person-name">${esc(v.fullName)}</div>
              <div class="person-sub">${v.phone ? esc(phoneFmt(v.phone)) + ' · ' : ''}${num(v.deliveriesCompleted)} ta yetkazgan · ${(v.ratingAvg || 0).toFixed(1)}★</div></div></a>
            <span class="small muted">${esc(ago(v.submittedAt))}</span>
          </div>
          <div class="small"><b>${esc(v.vehicleInfo || '')}</b> <span class="plate" style="font-size:13px;margin-left:6px"><span>${esc(v.plateNumber || '')}</span></span>
            ${v.userCreatedAt ? `<span class="tiny muted"> · YukGo'da ${esc(ago(v.userCreatedAt))} dan beri</span>` : ''}</div>
          <div class="ver-docs">
            <button type="button" data-doc="license" data-v="${v.id}">Guvohnomani ko'rish</button>
            <button type="button" data-doc="tech_passport" data-v="${v.id}">Tex-pasportni ko'rish</button>
          </div>
          <div class="row">
            <button class="btn btn-ok btn-sm" data-approve="${v.id}" data-name="${esc(v.fullName)}">${icon('check')}Tasdiqlash</button>
            <button class="btn btn-sm" data-reject="${v.id}" data-name="${esc(v.fullName)}">Rad etish</button>
          </div>
        </div>`).join('') : emptyState("Kutayotgan ariza yo'q", "Haydovchilar profilidagi «Tekshirilgan haydovchi» bo'limidan hujjat yuboradi.")}
    </section>
    ${d.recent.length ? `<section class="panel stack-sm"><h2>Oxirgi qarorlar</h2>
      <div class="table-wrap"><table><thead><tr><th>Haydovchi</th><th>Mashina</th><th>Qaror</th><th>Kim</th></tr></thead>
      <tbody>${d.recent.map(v => `<tr><td><a href="#/u/${v.userId}">${esc(v.fullName)}</a></td>
        <td class="small">${esc(v.vehicleInfo || '')}<br><span class="tiny muted">${esc(v.plateNumber || '')}</span></td>
        <td class="small">${v.status === 'APPROVED' ? '✅ Tasdiqlandi' : '❌ Rad etildi'}${v.rejectReason ? `<br><span class="tiny muted">${esc(v.rejectReason)}</span>` : ''}</td>
        <td class="small">${esc(v.decidedBy || '')}<br><span class="tiny muted">${esc(dateTime(v.decidedAt))}</span></td></tr>`).join('')}</tbody></table></div></section>` : ''}
  </div>`;

  $$('[data-doc]', pane).forEach(b => b.addEventListener('click', async () => {
    if (b.querySelector('img')) {   // ikkinchi bosish — kattalashtirib ko'rish
      modal(b.dataset.doc === 'license' ? 'Haydovchilik guvohnomasi' : 'Texnik pasport', `<img class="doc-view" src="${b.querySelector('img').src}" alt="">`);
      return;
    }
    b.textContent = 'Yuklanmoqda…';
    try {
      const u = await docUrl(b.dataset.v, b.dataset.doc);
      b.innerHTML = `<img src="${u}" alt="">`;
      b.title = 'Kattalashtirish uchun bosing';
    } catch (e) { b.textContent = e.message; }
  }));
  $$('[data-approve]', pane).forEach(b => b.addEventListener('click', async () => {
    const ok = await confirmDialog(`${b.dataset.name} tasdiqlansinmi?`,
      "Profilida «Tekshirilgan haydovchi» belgisi paydo bo'ladi, hujjat rasmlari o'chiriladi.", { okText: 'Tasdiqlash' });
    if (!ok) return;
    try { await post(`/admin/driver-verifications/${b.dataset.approve}/approve`); toast('Tasdiqlandi', 'ok'); verifyTab(pane, _owner, onCount); }
    catch (e) { toast(e.message, 'err'); }
  }));
  $$('[data-reject]', pane).forEach(b => b.addEventListener('click', async () => {
    const r = await confirmDialog(`${b.dataset.name} — rad etish`,
      "Sababini yozing — haydovchiga yuboriladi (masalan: «Rasm xira», «Raqam mos emas»). Rasmlar o'chiriladi, u qaytadan yubora oladi.",
      { danger: true, okText: 'Rad etish', withReason: true });
    if (!r) return;
    try {
      await api(`/admin/driver-verifications/${b.dataset.reject}/reject`, { method: 'POST', body: { reason: r.reason || null } });
      toast('Rad etildi', 'ok');
      verifyTab(pane, _owner, onCount);
    } catch (e) { toast(e.message, 'err'); }
  }));
}

/** Boshqaruv yorlig'idagi son uchun (sahifa ochilganda) */
export async function pendingVerifications() {
  try { return (await get('/admin/driver-verifications')).pending.length; } catch { return 0; }
}
