// Hujjatlar (foydalanish shartlari, maxfiylik siyosati) ilova ichida va shartlarni qabul qilish ekrani
import { auth, post, config } from './api.js';
import { $, modal, skeletons, toast, busy } from './ui.js';

/** Ilova ichida oynada ochiladigan hujjatlar */
const DOCS = new Set(['/terms.html', '/privacy.html', '/delete-account.html']);

/**
 * Hujjatni oyna ichida ochadi — sahifadan chiqib ketilmaydi (Android ilova va Telegram ichida ro'yxatdan o'tish
 * formasi yoki ochiq sahifa yo'qolmasin). Hujjatni yuklab bo'lmasa — shu oynada xato va «Qayta urinish».
 */
export async function openDoc(url) {
  const ctx = modal('Hujjat', skeletons(5), { wide: true });
  await loadDoc(ctx, url);
}

async function loadDoc(ctx, url) {
  try {
    const res = await fetch(url, { credentials: 'same-origin' });
    if (!res.ok) throw new Error(String(res.status));
    const page = new DOMParser().parseFromString(await res.text(), 'text/html');
    const art = page.querySelector('article');
    if (!art) throw new Error('article yo\'q');
    art.querySelectorAll('script, .back, a.btn').forEach(n => n.remove());
    const h1 = art.querySelector('h1');
    const title = h1 ? h1.textContent.trim() : 'Hujjat';
    h1?.remove();
    if (!document.body.contains(ctx.el)) return;   // kutmasdan yopildi
    $('.modal-head h2', ctx.el).textContent = title;
    ctx.el.setAttribute('aria-label', title);
    const body = $('.modal-body', ctx.el);
    body.innerHTML = `<div class="legal-doc">${art.innerHTML}</div>`;
    const se = $('#support-email', body);
    if (se) {
      se.removeAttribute('id');
      const c = await config();
      if (c && c.supportEmail) {
        const a = se.querySelector('a');
        a.href = 'mailto:' + c.supportEmail;
        a.textContent = c.supportEmail;
        se.hidden = false;
      }
    }
  } catch {
    // Sahifadan chiqib ketmaymiz (Android ilova va Telegram ichida ochiq forma yo'qolmasin) — xato shu oynada
    if (!document.body.contains(ctx.el)) return;
    const body = $('.modal-body', ctx.el);
    body.innerHTML = `<div class="stack"><p class="muted">Hujjatni ochib bo'lmadi. Internet aloqasini tekshirib, qayta urinib ko'ring.</p>
      <button class="btn" type="button" data-retry>Qayta urinish</button></div>`;
    $('[data-retry]', body).addEventListener('click', () => {
      body.innerHTML = skeletons(5);
      loadDoc(ctx, url);
    });
  }
}

/** data-doc belgili havolalar va hujjat ichidagi boshqa hujjatlarga havolalar — oyna ichida ochiladi */
export function bindDocLinks() {
  document.addEventListener('click', (e) => {
    if (e.defaultPrevented || e.button !== 0 || e.ctrlKey || e.metaKey || e.shiftKey || e.altKey) return;
    const a = e.target.closest && e.target.closest('a[href]');
    if (!a) return;
    const href = a.getAttribute('href');
    const inDoc = !!a.closest('.legal-doc');
    // Hujjat ichidagi ilova havolalari («YukGo'ga qaytish» va h.k.): ilovani qayta yuklamasdan — oynani yopamiz
    if (inDoc && (href === '/' || href.startsWith('/#'))) {
      e.preventDefault();
      a.closest('.modal')?.querySelector('[data-close]')?.click();
      if (href.startsWith('/#')) location.hash = href.slice(1);
      return;
    }
    if (!a.hasAttribute('data-doc') && !(inDoc && DOCS.has(href))) return;
    e.preventDefault();
    openDoc(href);
  });
}

/**
 * Shartlarni qabul qilish ekrani — shartlar paydo bo'lishidan oldin ro'yxatdan o'tganlar uchun (va shartlar
 * yangilanganda). Google Play talabi: e'lon, taklif, xabar va sharh yozishdan oldin shartlar qabul qilingan bo'lsin.
 */
export function termsGate(main, { onAccepted, onLogout, onDelete }) {
  main.innerHTML = `<div class="page page-narrow"><section class="panel stack terms-gate">
      <h1>Foydalanish shartlari</h1>
      <p>Davom etishdan oldin YukGo'dan foydalanish shartlari bilan tanishib chiqing va ularni qabul qiling.</p>
      <ul class="small">
        <li>Noqonuniy yuklar, firibgarlik, haqorat va tahdid, behayo kontent hamda spam taqiqlanadi.</li>
        <li>Qoidabuzar e'lon, xabar yoki foydalanuvchi haqida «Shikoyat qilish» orqali xabar bering, kerak bo'lsa uni bloklang.</li>
        <li>Qoidalarni buzganlarning kontenti o'chiriladi, hisobi bloklanadi.</li>
      </ul>
      <p class="small"><a href="/terms.html" data-doc>Foydalanish shartlari</a> · <a href="/privacy.html" data-doc>Maxfiylik siyosati</a></p>
      <button class="btn btn-primary btn-lg btn-block" id="terms-accept">Qabul qilaman</button>
      <button class="link-btn" id="terms-logout" style="justify-self:center">Chiqish</button>
      ${onDelete ? `<button class="link-btn danger-link" id="terms-delete" style="justify-self:center">Rozi emasman — hisobni o'chirish</button>` : ''}
    </section></div>`;
  const btn = $('#terms-accept', main);
  btn.addEventListener('click', async () => {
    try {
      auth.user = await busy(btn, () => post('/users/me/terms'));
      onAccepted();
    } catch (err) { toast(err.message, 'err'); }
  });
  $('#terms-logout', main).addEventListener('click', () => onLogout());
  $('#terms-delete', main)?.addEventListener('click', () => onDelete());
}
