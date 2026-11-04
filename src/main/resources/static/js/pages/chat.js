// E'lon bo'yicha yozishma: haydovchi «Olaman» bosishidan oldin ham e'lon egasi bilan yozisha oladi (har bir haydovchi
// bilan alohida). Telefon raqamlar shungacha yashirin: server xabar ichidagi raqamni ham yopib beradi.
import { auth, get, post } from '../api.js';
import { $, $$, esc, icon, avatar, stars, dateTime, ago, modal, toast, verifiedBadge } from '../ui.js';

const POLL_MS = 5000;

function msgHtml(m) {
  const mine = m.authorId === auth.user?.id;
  return `<div class="msg ${mine ? 'mine' : ''}">${esc(m.text)}<time>${esc(dateTime(m.createdAt))}</time></div>`;
}

/**
 * Yozishma oynasi (yopilganda yangilanish to'xtaydi).
 * @param {object} p
 * @param {number} p.orderId
 * @param {number|null} [p.driverId] e'lon egasi uchun — qaysi haydovchi bilan (haydovchining o'zi uchun berilmaydi)
 * @param {string} p.title
 * @param {string} [p.intro] oyna tepasidagi izoh (tayyor HTML)
 * @param {boolean} [p.canWrite]
 * @param {string} [p.closedText] yozib bo'lmasa — sababi
 * @param {string} [p.emptyText] hali xabar yo'q bo'lsa
 * @param {() => void} [p.onSent]
 */
export function openChat({ orderId, driverId = null, title, intro = '', canWrite = true, closedText = '', emptyText = '', onSent }) {
  let lastId = 0, timer = null, closed = false, loaded = false;
  const ctx = modal(title, `<div class="stack-sm">
      ${intro ? `<div class="small muted">${intro}</div>` : ''}
      <div class="chat chat-modal">
        <div class="chat-list" data-list aria-live="polite"><p class="muted small chat-empty" data-empty>Yuklanmoqda…</p></div>
        ${canWrite
          ? `<form class="chat-form" data-form><input class="input" name="text" maxlength="1000" placeholder="Xabar yozing…" autocomplete="off" aria-label="Xabar">
             <button class="btn btn-route" type="submit">Yuborish</button></form>`
          : `<div class="chat-closed small" role="status">${esc(closedText || 'Yozishma yopilgan')}</div>`}
      </div>
      <p class="phone-lock">${icon('shield')}Telefon raqamlar «Olaman» bosilgach ochiladi — xabarga yozilgan raqam ham shungacha yashirin.</p>
    </div>`, {
    onClose: () => { closed = true; clearInterval(timer); },
  });
  const list = $('[data-list]', ctx.el);
  const form = $('[data-form]', ctx.el);

  async function pull() {
    if (closed || !document.body.contains(list)) { clearInterval(timer); return; }
    try {
      const msgs = await get(`/orders/${orderId}/messages`, { after: lastId, driverId: driverId ?? undefined });
      if (closed) return;
      const empty = $('[data-empty]', list);
      if (!loaded) {
        loaded = true;
        if (!msgs.length && empty) empty.textContent = emptyText || "Hali xabar yo'q.";
      }
      let added = false;
      for (const m of msgs) {
        if (m.id <= lastId) continue;   // ikki so'rov bir vaqtda qaytsa — takrorlanmasin
        lastId = m.id;
        list.insertAdjacentHTML('beforeend', msgHtml(m));
        added = true;
      }
      if (added) {
        empty?.remove();
        list.scrollTop = list.scrollHeight;
      }
    } catch (e) {
      if (!loaded) {
        loaded = true;
        const empty = $('[data-empty]', list);
        if (empty) empty.textContent = e.message;
      }
    }
  }

  form?.addEventListener('submit', async (e) => {
    e.preventDefault();
    const text = form.text.value.trim();
    if (!text) return;
    const btn = form.querySelector('[type=submit]');
    btn.disabled = true;
    form.text.value = '';
    try {
      await post(`/orders/${orderId}/messages`, { text, driverId });
      await pull();
      if (onSent) onSent();
    } catch (err) {
      toast(err.message, 'err');
      form.text.value = text;
    } finally {
      btn.disabled = false;
      if (!closed) form.text.focus();
    }
  });

  pull();
  timer = setInterval(pull, POLL_MS);
  return ctx;
}

/**
 * E'lon egasi uchun: unga yozgan haydovchilar (oxirgi xabar, «Javob kutmoqda»). Bosilganda — onOpen(thread).
 * Yozgan haydovchi bo'lmasa blok ko'rsatilmaydi.
 * @returns {Promise<Array>} iplar ro'yxati
 */
export async function loadThreads(box, o, { onOpen } = {}) {
  let list;
  try { list = await get(`/orders/${o.id}/threads`); } catch { return []; }
  if (!box || !document.body.contains(box)) return list;
  const passenger = o.kind === 'PASSENGER';
  box.innerHTML = list.length ? `<section class="panel stack-sm">
      <div class="row-between"><h2>Yozishmalar</h2><span class="muted small">${list.length} ta haydovchi</span></div>
      <p class="small muted">Haydovchilar ${passenger ? 'safar' : 'e\'lon'} haqida so'rashi mumkin. Raqamingiz yashirin — haydovchi «Olaman» bosganda ochiladi.</p>
      <div class="threads">${list.map(threadRow).join('')}</div></section>` : '';
  $$('[data-thread]', box).forEach(b => b.addEventListener('click', () => {
    const t = list.find(x => x.driverId === Number(b.dataset.thread));
    if (t && onOpen) onOpen(t);
  }));
  return list;
}

function threadRow(t) {
  const d = t.driver;
  const waiting = !t.lastMine;
  const car = d.vehicle ? `${d.vehicle.brand}${d.vehicle.model ? ' ' + d.vehicle.model : ''}` : '';
  return `<button type="button" class="thread${waiting ? ' is-waiting' : ''}" data-thread="${t.driverId}">
    ${avatar(d.avatarUrl, d.fullName)}
    <span class="thread-main">
      <span class="thread-top"><b>${esc(d.fullName)}</b>${verifiedBadge(d.verified, { short: true })}<span class="tiny muted">${esc(ago(t.lastAt))}</span></span>
      <span class="thread-sub">${car ? esc(car) + ' · ' : ''}${stars(d.rating, d.ratingCount)}</span>
      <span class="thread-text">${t.lastMine ? 'Siz: ' : ''}${esc(t.lastText)}</span>
      ${waiting ? '<span class="thread-badge">Javob kutmoqda</span>' : ''}
    </span>
  </button>`;
}

/** Haydovchi uchun izoh: kimga yozayotgani va raqamlar haqida */
export function driverChatIntro(o) {
  const passenger = o.kind === 'PASSENGER';
  return `${passenger ? "Yo'lovchidan" : "E'lon egasidan"} qayerdan olishni, vaqtni yoki narxni so'rashingiz mumkin. Javobi shu yozishmada chiqadi.`;
}
