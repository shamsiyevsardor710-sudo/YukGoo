// «Xabarlar»: barcha yozishmalarim bir joyda — e'lonim bo'yicha haydovchilar bilan va haydovchi sifatida e'lon egalari
// bilan. Bosilganda e'lon sahifasida o'sha yozishma ochiladi. O'qilmaganlar soni tepadagi 💬 belgisida ham ko'rinadi.
import { get } from '../api.js';
import { $, esc, avatar, ago, verifiedBadge, emptyState, skeletons } from '../ui.js';
import { refreshUnread } from '../app.js';

const POLL_MS = 20000;
const KIND_MARK = { PASSENGER: '🧍', PARCEL: '✉️', CARGO: '📦' };

export async function messagesPage(main) {
  main.innerHTML = `<div class="page stack">
    <div class="page-head"><div><h1>Xabarlar</h1>
      <p class="muted">E'lonlar bo'yicha yozishmalar. Telefon raqamlar haydovchi «Olaman» bosgach ochiladi.</p></div></div>
    <section class="panel"><div class="threads inbox" id="inbox">${skeletons(3)}</div></section>
  </div>`;
  const box = $('#inbox', main);
  let alive = true;

  async function load() {
    let list;
    try {
      list = await get('/messages');
    } catch (e) {
      if (alive && !box.querySelector('.thread')) box.innerHTML = `<p class="muted">${esc(e.message)}</p>`;
      return;
    }
    if (!alive) return;
    box.innerHTML = list.length ? list.map(inboxRow).join('')
      : emptyState("Hali yozishma yo'q",
        "Haydovchi e'loningiz haqida so'rasa yoki siz e'lon egasiga yozsangiz — yozishma shu yerda ko'rinadi.",
        '<a class="btn" href="#/">E\'lonlarni ko\'rish</a>');
    refreshUnread();
  }

  await load();
  const timer = setInterval(() => { if (!document.hidden) load(); }, POLL_MS);
  return () => { alive = false; clearInterval(timer); };
}

/** Bitta yozishma qatori (sinov uchun eksport) */
export function inboxRow(t) {
  const c = t.counterpart || {};
  const role = t.iAmSender ? "E'loningiz" : 'Siz — haydovchi';
  const state = !t.open ? '<span class="thread-badge is-closed">Yopilgan</span>'
    : t.assigned ? `<span class="thread-badge is-agreed">${t.iAmSender ? 'Haydovchingiz' : 'Sizga berilgan'}</span>` : '';
  const unread = t.unread > 0 ? `<span class="count-badge" aria-label="${t.unread} ta o'qilmagan">${t.unread > 99 ? '99+' : t.unread}</span>` : '';
  return `<a class="thread${t.unread > 0 ? ' is-unread' : ''}" href="#/orders/${encodeURIComponent(t.orderId)}?chat=${encodeURIComponent(t.driverId)}">
    ${avatar(c.avatarUrl, c.fullName)}
    <span class="thread-main">
      <span class="thread-top"><b>${esc(c.fullName)}</b>${verifiedBadge(c.verified, { short: true })}<span class="tiny muted">${esc(ago(t.lastAt))}</span></span>
      <span class="thread-sub">${KIND_MARK[t.kind] || '📦'} ${esc(t.fromName)} → ${esc(t.toName)} · ${esc(t.title)} · ${role}</span>
      <span class="thread-text">${t.lastMine ? 'Siz: ' : ''}${esc(t.lastText)}</span>
      ${state}
    </span>
    ${unread}
  </a>`;
}
