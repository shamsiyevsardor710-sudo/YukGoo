// Balansni to'ldirish: Click, Payme (to'lov sahifasi) va Paynet (ilova yoki shoxobcha, hisob raqami — YukGo ID)
import { api, post } from '../api.js';
import { $, $$, esc, icon, modal, money, num, showError, clearError, busy, copyText, toast } from '../ui.js';

const METHODS = {
  CLICK: { label: 'Click', hint: 'Click ilovasi yoki bank kartasi' },
  PAYME: { label: 'Payme', hint: 'Payme ilovasi yoki bank kartasi' },
  PAYNET: { label: 'Paynet', hint: "Paynet ilovasi yoki to'lov shoxobchasi" },
};
const CHIPS = [20000, 50000, 100000, 200000];
const POLL_MS = 3000;
/** To'lov sahifasi ochilgandan keyin holatni shuncha vaqt kuzatamiz */
const WATCH_MS = 20 * 60 * 1000;

/** Hech bo'lmasa bitta to'lov usuli ulanganmi */
export function topupAvailable(opts) {
  return !!opts && !!(opts.click || opts.payme || opts.paynet);
}

const digits = (s) => String(s || '').replace(/\D/g, '');

/** Summa va usulni tanlash. onPaid — pul tushganda (balans blokini yangilash uchun) */
export function openTopup(opts, onPaid) {
  const methods = Object.keys(METHODS).filter(k => opts[k.toLowerCase()]);
  const chips = CHIPS.filter(v => v >= opts.min && v <= opts.max);
  modal("Balansni to'ldirish", `<form class="stack" id="tp-form" novalidate>
      <label class="field"><span>Summa, so'm</span>
        <input class="input num" name="amount" id="tp-amount" inputmode="numeric" autocomplete="off" placeholder="Masalan, 50 000"></label>
      ${chips.length ? `<div class="amount-chips">${chips.map(v => `<button type="button" data-v="${v}">${esc(num(v))}</button>`).join('')}</div>` : ''}
      <p class="tiny muted" style="margin:0">Kamida ${esc(money(opts.min))}, ko'pi bilan ${esc(money(opts.max))}.</p>
      <div class="stack-sm"><b>To'lov usuli</b>
        <div class="pay-methods">${methods.map(k => `<button type="button" class="pay-method" data-p="${k}">
          <span><b>${esc(METHODS[k].label)}</b><small>${esc(METHODS[k].hint)}</small></span><i aria-hidden="true">›</i></button>`).join('')}</div>
      </div>
    </form>`, { onMount: (ctx) => bindForm(ctx, opts, onPaid) });
}

function bindForm(ctx, opts, onPaid) {
  const form = $('#tp-form', ctx.el);
  const input = $('#tp-amount', ctx.el);
  const markChips = (v) => $$('.amount-chips button', ctx.el).forEach(b => b.classList.toggle('on', b.dataset.v === String(v)));
  input.addEventListener('input', () => {
    const d = digits(input.value).slice(0, 10);
    input.value = d ? num(Number(d)) : '';
    markChips(d);
  });
  $$('.amount-chips button', ctx.el).forEach(b => b.addEventListener('click', () => {
    input.value = num(Number(b.dataset.v));
    input.classList.remove('invalid');
    markChips(b.dataset.v);
    clearError(form);
  }));
  form.addEventListener('submit', (e) => e.preventDefault());
  $$('.pay-method', ctx.el).forEach(b => b.addEventListener('click', () => {
    clearError(form);
    const amount = Number(digits(input.value));
    const bad = !amount ? 'Summani kiriting'
      : amount < opts.min || amount > opts.max ? `Summa ${money(opts.min)} dan ${money(opts.max)} gacha bo'lsin` : null;
    if (bad) {
      showError(form, new Error(bad));
      input.classList.add('invalid');
      input.focus();
      return;
    }
    busy(b, async () => {
      try {
        const r = await post('/payments/topup', { provider: b.dataset.p, amount });
        if (r.provider === 'PAYNET') await paynetStep(ctx, r, onPaid);
        else payStep(ctx, r, onPaid);
      } catch (err) { showError(form, err); }
    });
  }));
}

function sumBlock(amount, provider) {
  return `<div class="pay-sum"><span class="small muted">To'lov summasi</span>
    <b class="balance num">${esc(money(amount))}</b><span class="small muted">${esc(METHODS[provider]?.label || '')} orqali</span></div>`;
}

/** Click / Payme: to'lov sahifasi havolasi va holatni kuzatish */
function payStep(ctx, r, onPaid) {
  const label = METHODS[r.provider].label;
  $('.modal-body', ctx.el).innerHTML = `<div class="stack">
      ${sumBlock(r.amount, r.provider)}
      <a class="btn btn-primary btn-lg btn-block" id="tp-go" href="${esc(r.payUrl)}" target="_blank" rel="noopener">${esc(label)} orqali to'lash</a>
      <p class="small muted" style="margin:0">To'lov sahifasi alohida oynada ochiladi. To'lagach, shu yerga qayting — balans o'zi yangilanadi.</p>
      <div class="pay-status" id="tp-status" aria-live="polite"><span class="waiting">To'lov kutilmoqda…</span></div>
    </div>`;
  watchPayment(ctx, r.id, onPaid, WATCH_MS);
}

/** Paynet: buyurtma oldindan yaratilmaydi — foydalanuvchi ID si bilan to'laydi, biz yangi Paynet to'lovi kelishini kuzatamiz */
async function paynetStep(ctx, r, onPaid) {
  // Oyna ochilgan paytdagi oxirgi Paynet to'lovi — shundan keyingisi «yangi» (balansning boshqa o'zgarishlari hisobga olinmaydi)
  let lastId = null;
  try { lastId = (await api('/payments/latest', { query: { provider: 'PAYNET' }, silent: true }))?.id || 0; } catch { /* */ }
  $('.modal-body', ctx.el).innerHTML = `<div class="stack">
      ${sumBlock(r.amount, 'PAYNET')}
      <ol class="pay-steps">
        <li>Paynet ilovasi yoki to'lov shoxobchasida <b>YukGo</b> xizmatini tanlang.</li>
        <li>Hisob raqami — YukGo ID ingiz: <b class="num">${esc(r.paynetAccount)}</b>
          <button type="button" class="link-btn" id="tp-copy">${icon('copy', 'style="width:15px;height:15px;vertical-align:-2px"')} Nusxa olish</button></li>
        <li>${esc(money(r.amount))} to'lang — pul balansingizga darhol tushadi.</li>
      </ol>
      ${r.payUrl ? `<a class="btn btn-primary btn-block" href="${esc(r.payUrl)}" target="_blank" rel="noopener">Paynet ilovasini ochish</a>` : ''}
      <div class="pay-status" id="tp-status" aria-live="polite"><span class="waiting">To'lov kutilmoqda…</span></div>
    </div>`;
  $('#tp-copy', ctx.el).addEventListener('click', async () => {
    const ok = await copyText(String(r.paynetAccount));
    toast(ok ? 'ID nusxalandi' : "Nusxa olib bo'lmadi", ok ? 'ok' : 'err');
  });
  if (lastId != null) watchPaynet(ctx, lastId, onPaid);
}

/** To'lov sahifasidan qaytganda (#/profile?topup=ID) — to'lov holati */
export function topupReturn(id, onPaid) {
  const ctx = modal("To'lov holati", `<div class="stack">
      <div class="pay-status" id="tp-status" aria-live="polite"><span class="waiting">To'lov tekshirilmoqda…</span></div>
      <button class="btn btn-block" data-close>Yopish</button></div>`);
  watchPayment(ctx, id, onPaid, 2 * 60 * 1000);
}

/** Oyna ochiq turgan paytda to'lov holatini so'rab turish; boshqa ilovadan qaytilganda — darhol */
function watchPayment(ctx, id, onPaid, limitMs) {
  const started = Date.now();
  let timer = null;
  let stopped = false;
  const stop = () => { stopped = true; clearTimeout(timer); document.removeEventListener('visibilitychange', onVisible); };
  const alive = () => !stopped && document.body.contains(ctx.el);
  const status = (cls, html) => { const box = $('#tp-status', ctx.el); if (box) { box.className = 'pay-status' + (cls ? ' ' + cls : ''); box.innerHTML = html; } };
  const tick = async () => {
    if (!alive()) return stop();
    let p = null;
    try {
      p = await api('/payments/' + id, { silent: true });
    } catch (err) {
      if (err.status === 404) { status('err', "To'lov topilmadi."); return stop(); }
    }
    if (!alive()) return stop();
    if (p && p.status === 'PAID') { stop(); return paid(ctx, p.amount, p.provider, onPaid); }
    if (p && p.status === 'CANCELLED') status('err', "To'lov amalga oshmadi yoki bekor qilindi. Qayta urinib ko'rishingiz mumkin.");
    else if (p && p.status === 'PENDING') status('', '<span class="waiting">To\'lov tasdiqlanmoqda…</span>');
    if (Date.now() - started > limitMs) {
      if (!p || p.status !== 'CANCELLED') status('', "To'lov hali tasdiqlanmadi. Pul tushishi bilan balansingiz yangilanadi va xabar keladi.");
      return stop();
    }
    timer = setTimeout(tick, POLL_MS);
  };
  const onVisible = () => {
    if (document.visibilityState === 'visible' && alive()) { clearTimeout(timer); tick(); }
  };
  document.addEventListener('visibilitychange', onVisible);
  timer = setTimeout(tick, 1200);
}

/** Paynet: shu foydalanuvchiga yangi Paynet to'lovi tushganini kuzatish */
function watchPaynet(ctx, lastId, onPaid) {
  const started = Date.now();
  const tick = async () => {
    if (!document.body.contains(ctx.el)) return;
    try {
      const p = await api('/payments/latest', { query: { provider: 'PAYNET' }, silent: true });
      if (p && p.id > lastId && p.status === 'PAID') return paid(ctx, p.amount, 'PAYNET', onPaid);
    } catch { /* */ }
    if (Date.now() - started < WATCH_MS) setTimeout(tick, 5000);
    else {
      const box = $('#tp-status', ctx.el);
      if (box) box.textContent = "Pul tushishi bilan balansingiz yangilanadi va xabar keladi.";
    }
  };
  setTimeout(tick, 5000);
}

function paid(ctx, amount, provider, onPaid) {
  const body = $('.modal-body', ctx.el);
  if (body) {
    body.innerHTML = `<div class="stack pay-done">
        <span class="pay-done-mark">${icon('check')}</span>
        <h3>Balans to'ldirildi</h3>
        <p class="muted">+${esc(money(amount))}${METHODS[provider] ? ' — ' + esc(METHODS[provider].label) + ' orqali' : ''}</p>
        <button class="btn btn-block" data-close>Yopish</button></div>`;
  }
  if (onPaid) onPaid();
}
