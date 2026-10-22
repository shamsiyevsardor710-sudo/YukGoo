// Kirish, ro'yxatdan o'tish (Telegram kod bilan) va parolni tiklash
import { auth, post, get, config } from '../api.js';
import { $, esc, icon, toast, formData, showError, clearError, busy, bindRegionDistrict, phoneFmt, money } from '../ui.js';
import { afterLogin, go } from '../app.js';
import { tg, telegramInitData, telegramLoggedIn } from '../telegram.js';
import { attribution, clearAttribution } from '../attribution.js';

function shell(main, inner) {
  main.innerHTML = `<div class="auth-wrap"><div class="auth-card">${inner}</div></div>`;
  return $('.auth-card', main);
}

// =====================================================================
// Kirish
// =====================================================================
export function loginPage(main) {
  const card = shell(main, `
    <h1>Kirish</h1>
    <p class="muted" style="margin-bottom:20px">Ism, familiya va parolingizni kiriting.</p>
    ${tg.active ? `<p class="tg-note">${icon('telegram')}<span>${esc(tg.loginHint || "Bir marta parol bilan kiring — keyingi safar YukGo sizni Telegram orqali o'zi taniydi.")}</span></p>` : ''}
    <form class="stack" id="login-form" novalidate>
      <div class="form-grid" id="by-name">
        <label class="field"><span>Ism</span><input class="input" name="firstName" autocomplete="given-name" required></label>
        <label class="field"><span>Familiya</span><input class="input" name="lastName" autocomplete="family-name" required></label>
      </div>
      <label class="field hidden" id="by-phone"><span>Telefon raqam</span>
        <input class="input" name="phone" type="tel" inputmode="tel" placeholder="+998 90 123 45 67" autocomplete="tel"></label>
      <label class="field"><span>Parol</span><input class="input" name="password" type="password" autocomplete="current-password" required></label>
      <button class="btn btn-primary btn-lg btn-block" type="submit">Kirish</button>
      <div class="row-between small">
        <button type="button" class="link-btn" id="toggle-mode">Telefon raqam bilan kirish</button>
        <a href="#/forgot">Parolni unutdingizmi?</a>
      </div>
    </form>
    <p class="small muted" style="margin-top:22px;text-align:center">Hisobingiz yo'qmi? <a href="#/register">Ro'yxatdan o'ting</a></p>`);

  const form = $('#login-form', card);
  let byPhone = false;
  $('#toggle-mode', card).addEventListener('click', (e) => {
    byPhone = !byPhone;
    $('#by-name', card).classList.toggle('hidden', byPhone);
    $('#by-phone', card).classList.toggle('hidden', !byPhone);
    e.target.textContent = byPhone ? 'Ism-familiya bilan kirish' : 'Telefon raqam bilan kirish';
  });
  form.addEventListener('submit', async (e) => {
    e.preventDefault();
    clearError(form);
    const d = formData(form);
    const body = byPhone ? { phone: d.phone, password: d.password }
      : { firstName: d.firstName, lastName: d.lastName, password: d.password };
    // Telegram ichida: parol shu so'rovda tekshiriladi va hisob shu Telegramga ulanadi (keyingi safar o'zi kiradi)
    body.telegramInitData = telegramInitData();
    try {
      await busy(form.querySelector('[type=submit]'), async () => {
        const res = await post('/auth/login', body);
        auth.token = res.token;
        auth.user = res.user;
      });
      telegramLoggedIn();
      toast(`Xush kelibsiz, ${auth.user.firstName}!`, 'ok');
      afterLogin();
    } catch (err) {
      if (err.status === 400 && /telefon raqam bilan/i.test(err.message) && !byPhone) {
        $('#toggle-mode', card).click();
      }
      showError(form, err);
    }
  });
}

// =====================================================================
// Telefonni Telegram orqali tasdiqlash (qayta ishlatiladigan komponent)
// =====================================================================
/**
 * @param el        joylashadigan konteyner
 * @param startFn   (phone) => Promise<StartResult>
 * @param verifyFn  (requestId, code) => Promise
 * @param onDone    (requestId) => void
 */
export function phoneVerification(el, { startFn, verifyFn, onDone, onCodeStep, phoneLabel = 'Telefon raqamingiz', presetPhone = '' }) {
  let state = null;
  let poll = null;
  let cooldownTimer = null;

  const stop = () => { clearInterval(poll); clearInterval(cooldownTimer); };

  function phoneStep() {
    el.innerHTML = `<form class="stack" novalidate>
      <label class="field"><span>${esc(phoneLabel)}</span>
        <input class="input" name="phone" type="tel" inputmode="tel" placeholder="+998 90 123 45 67" value="${esc(presetPhone)}" autocomplete="tel" required>
        <small>Tasdiqlash kodi SMS emas, <b>Telegram bot</b> orqali keladi.</small></label>
      <button class="btn btn-primary btn-lg btn-block" type="submit">Kod olish</button></form>`;
    const form = $('form', el);
    form.addEventListener('submit', async (e) => {
      e.preventDefault();
      clearError(form);
      const phone = form.phone.value.trim();
      try {
        state = await busy(form.querySelector('[type=submit]'), () => startFn(phone));
        state.phone = phone;
        codeStep();
      } catch (err) { showError(form, err); }
    });
  }

  function codeStep() {
    if (onCodeStep) onCodeStep();
    const tgBlock = state.codeSent ? `
      <div class="form-note">${icon('telegram', 'style="width:16px;height:16px;vertical-align:-3px"')}
        Kod <b>@${esc(state.botUsername)}</b> botiga yuborildi. Telegramni oching.</div>`
      : `<div class="tg-step" id="tg-step">
          <b>Telegram botdan kod oling</b>
          <ol>
            <li>Pastdagi tugma orqali <b>@${esc(state.botUsername)}</b> botini oching va <b>Start</b> bosing.</li>
            <li>Botda <b>«📱 Telefon raqamni yuborish»</b> tugmasini bosing.</li>
            <li>Bot sizga 6 xonali kod yuboradi — uni shu yerga kiriting.</li>
          </ol>
          <a class="btn btn-route btn-block" href="${esc(state.botLink)}" target="_blank" rel="noopener">${icon('telegram')}Telegram botni ochish</a>
          <span class="waiting" id="tg-wait">Bot orqali kod yuborilishi kutilmoqda…</span>
        </div>`;
    el.innerHTML = `<form class="stack" novalidate>
      <p class="small muted">Raqam: <b>${esc(phoneFmt(state.phone) || state.phone)}</b> · <button type="button" class="link-btn" id="change-phone">o'zgartirish</button></p>
      ${tgBlock}
      ${state.devCode ? `<div class="form-note">Sinov rejimi (bot ulanmagan): kod <b class="num">${esc(state.devCode)}</b></div>` : ''}
      <label class="field"><span>Tasdiqlash kodi</span>
        <input class="input code-input" name="code" inputmode="numeric" autocomplete="one-time-code" maxlength="6" pattern="\\d{6}" value="${esc(state.devCode || '')}" required></label>
      <button class="btn btn-primary btn-lg btn-block" type="submit">Tasdiqlash</button>
      <button type="button" class="btn btn-ghost btn-sm" id="resend">Kodni qayta yuborish</button>
    </form>`;
    const form = $('form', el);
    $('#change-phone', el).addEventListener('click', () => { stop(); phoneStep(); });
    form.code.addEventListener('input', () => {
      form.code.value = form.code.value.replace(/\D/g, '').slice(0, 6);
      if (form.code.value.length === 6) form.requestSubmit();
    });
    form.addEventListener('submit', async (e) => {
      e.preventDefault();
      clearError(form);
      try {
        await busy(form.querySelector('[type=submit]'), () => verifyFn(state.requestId, form.code.value));
        stop();
        onDone(state.requestId, form.code.value);
      } catch (err) { showError(form, err); }
    });
    const resend = $('#resend', el);
    resend.addEventListener('click', async () => {
      clearError(form);
      try {
        const r = await post('/auth/resend', { requestId: state.requestId });
        Object.assign(state, r);
        if (r.devCode) form.code.value = r.devCode;
        toast(r.codeSent ? 'Kod qayta yuborildi' : 'Avval botda raqamingizni yuboring');
        cooldown();
      } catch (err) { showError(form, err); }
    });
    cooldown();
    if (!state.codeSent && !state.devCode) {
      poll = setInterval(async () => {
        try {
          const s = await get(`/auth/verification/${state.requestId}`);
          if (s.codeSent) {
            clearInterval(poll);
            const w = $('#tg-wait', el);
            if (w) { w.classList.remove('waiting'); w.innerHTML = '✅ Kod Telegramga yuborildi'; }
            form.code.focus();
          }
        } catch { clearInterval(poll); }
      }, 3000);
    } else {
      form.code.focus();
    }

    function cooldown() {
      let left = 45;
      resend.disabled = true;
      clearInterval(cooldownTimer);
      cooldownTimer = setInterval(() => {
        left--;
        resend.textContent = left > 0 ? `Kodni qayta yuborish (${left})` : 'Kodni qayta yuborish';
        if (left <= 0) { resend.disabled = false; clearInterval(cooldownTimer); }
      }, 1000);
    }
  }

  phoneStep();
  return stop;
}

// =====================================================================
// Ro'yxatdan o'tish: telefon → kod → ma'lumotlar va manzil
// =====================================================================

/** Yangi foydalanuvchiga imtiyozlar (admin yoqqan bo'lsa): taklif bonusi, komissiyasiz birinchi buyurtmalar */
async function showPerks(box) {
  const cfg = await config().catch(() => null);
  const pr = cfg?.pricing?.enabled ? cfg.pricing : null;
  const notes = [];
  if (attribution().ref) {
    notes.push(pr?.referralBonus > 0
      ? `🤝 Sizni do'stingiz taklif qildi: birinchi buyurtmangiz yakunlanganda ikkalangizga <b>${esc(money(pr.referralBonus))}</b> bonus.`
      : "🤝 Sizni do'stingiz taklif qildi — xush kelibsiz!");
  }
  if (pr?.freeFirstDeliveries > 0) notes.push(`🚚 Yangi haydovchilarga: birinchi <b>${pr.freeFirstDeliveries} ta</b> buyurtma komissiyasiz.`);
  if (pr?.firstUrgentFree && pr.urgentFee > 0) notes.push("🔥 Yuk egalariga: birinchi «Shoshilinch» e'lon bepul.");
  if (box && notes.length) box.innerHTML = notes.map(n => `<div class="perk">${n}</div>`).join('');
}
export function registerPage(main) {
  const card = shell(main, `
    <div class="steps" aria-hidden="true"><i class="on"></i><i></i><i></i></div>
    <h1>Ro'yxatdan o'tish</h1>
    <div id="perks" class="stack-sm"></div>
    <p class="muted" id="step-text" style="margin-bottom:20px">Telefon raqamingizni tasdiqlaymiz.</p>
    <div id="step"></div>
    <p class="small muted" style="margin-top:22px;text-align:center">Hisobingiz bormi? <a href="#/login">Kirish</a></p>
    <p class="tiny muted" style="margin-top:10px;text-align:center">Ro'yxatdan o'tib, <a href="/privacy.html">maxfiylik siyosati</a>ga rozilik bildirasiz.</p>`);
  showPerks($('#perks', card));
  const steps = card.querySelectorAll('.steps i');
  const setStep = (n, text) => {
    steps.forEach((s, i) => s.classList.toggle('on', i < n));
    $('#step-text', card).textContent = text;
  };

  const stop = phoneVerification($('#step', card), {
    startFn: (phone) => post('/auth/register/start', { phone }),
    verifyFn: (requestId, code) => post('/auth/verify', { requestId, code }),
    onDone: (requestId) => detailsStep(requestId),
    onCodeStep: () => setStep(2, 'Telegramdan kelgan kodni kiriting.'),
  });

  async function detailsStep(requestId) {
    setStep(3, "Oxirgi qadam: ism, parol va yashash manzilingiz.");
    const box = $('#step', card);
    box.innerHTML = `<form class="stack" novalidate>
      <div class="form-grid">
        <label class="field"><span>Ism</span><input class="input" name="firstName" autocomplete="given-name" required maxlength="60"></label>
        <label class="field"><span>Familiya</span><input class="input" name="lastName" autocomplete="family-name" required maxlength="60"></label>
        <label class="field"><span>Parol</span><input class="input" name="password" type="password" autocomplete="new-password" minlength="6" required></label>
        <label class="field"><span>Parolni takrorlang</span><input class="input" name="password2" type="password" autocomplete="new-password" required></label>
      </div>
      <p class="small muted">Tizimga ism, familiya va parol bilan kirasiz.</p>
      <div class="form-grid">
        <label class="field"><span>Viloyat</span><select class="select" name="regionId" required></select></label>
        <label class="field"><span>Tuman / shahar</span><select class="select" name="districtId" required disabled></select></label>
        <label class="field"><span>Qishloq / mahalla</span><input class="input" name="village" maxlength="120" required></label>
        <label class="field"><span>Ko'cha, uy raqami</span><input class="input" name="house" maxlength="120" placeholder="Navoiy ko'chasi, 12-uy" required></label>
      </div>
      <button class="btn btn-primary btn-lg btn-block" type="submit">Ro'yxatdan o'tish</button>
    </form>`;
    const form = $('form', box);
    await bindRegionDistrict(form.regionId, form.districtId);
    form.addEventListener('submit', async (e) => {
      e.preventDefault();
      clearError(form);
      const d = formData(form);
      if (d.password !== d.password2) return showError(form, { message: 'Parollar bir xil emas' });
      try {
        const res = await busy(form.querySelector('[type=submit]'), () => post('/auth/register/complete', {
          requestId, firstName: d.firstName, lastName: d.lastName, password: d.password,
          regionId: Number(d.regionId) || null, districtId: Number(d.districtId) || null,
          village: d.village, house: d.house, telegramInitData: telegramInitData(),
          ...attribution(),   // qaysi kanal/hamkor/do'st havolasi bilan kelgani
        }));
        auth.token = res.token;
        auth.user = res.user;
        telegramLoggedIn();
        clearAttribution();
        toast("Ro'yxatdan o'tdingiz! Xush kelibsiz", 'ok');
        afterLogin('#/profile');   // botdan kelgan to'ldirilgan forma yoki buyurtma bo'lsa — o'sha yerga
      } catch (err) { showError(form, err); }
    });
  }
  return stop;
}

// =====================================================================
// Parolni tiklash
// =====================================================================
export function forgotPage(main) {
  const card = shell(main, `
    <h1>Parolni tiklash</h1>
    <p class="muted" style="margin-bottom:20px">Ro'yxatdan o'tgan raqamingizga Telegram orqali kod yuboramiz.</p>
    <div id="step"></div>
    <p class="small muted" style="margin-top:22px;text-align:center"><a href="#/login">Kirish sahifasiga qaytish</a></p>`);
  return phoneVerification($('#step', card), {
    startFn: (phone) => post('/auth/reset/start', { phone }),
    verifyFn: (requestId, code) => post('/auth/verify', { requestId, code }),
    onDone: (requestId) => {
      const box = $('#step', card);
      box.innerHTML = `<form class="stack" novalidate>
        <label class="field"><span>Yangi parol</span><input class="input" name="p1" type="password" minlength="6" autocomplete="new-password" required></label>
        <label class="field"><span>Yangi parolni takrorlang</span><input class="input" name="p2" type="password" autocomplete="new-password" required></label>
        <button class="btn btn-primary btn-lg btn-block" type="submit">Parolni saqlash</button></form>`;
      const form = $('form', box);
      form.addEventListener('submit', async (e) => {
        e.preventDefault();
        clearError(form);
        if (form.p1.value !== form.p2.value) return showError(form, { message: 'Parollar bir xil emas' });
        try {
          const res = await busy(form.querySelector('[type=submit]'), () =>
            post('/auth/reset/complete', { requestId, newPassword: form.p1.value, telegramInitData: telegramInitData() }));
          auth.token = res.token;
          auth.user = res.user;
          telegramLoggedIn();
          toast('Parol yangilandi', 'ok');
          go('#/');
        } catch (err) { showError(form, err); }
      });
    },
  });
}
