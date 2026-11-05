// «Biz bilan bog'lanish»: foydalanuvchi va haydovchilar taklif, muammo yoki savol yozadi (yoki qo'ng'iroq qiladi);
// ega va adminlar «Haydovchilardan» / «Foydalanuvchilardan» bo'limlarida o'qiydi, javob yozadi yoki yopadi.
import { get, post, config } from '../api.js';
import { $, $$, esc, icon, avatar, toast, modal, busy, showError, clearError, phoneFmt, ago, emptyState, skeletons } from '../ui.js';

export const TOPIC = { IDEA: 'Taklif', PROBLEM: 'Muammo', QUESTION: 'Savol', OTHER: 'Boshqa' };
const TOPIC_MARK = { IDEA: '💡', PROBLEM: '⚠️', QUESTION: '❓', OTHER: '💬' };
const TOPIC_HINT = {
  IDEA: "Masalan: «Reyslar bo'limida qaytish yo'lini ham qo'shsangiz yaxshi bo'lardi»",
  PROBLEM: "Nima bo'ldi va qaysi sahifada? Masalan: «Taklif yuborganimda xato chiqdi»",
  QUESTION: 'Savolingizni yozing',
  OTHER: 'Xabaringizni yozing',
};
/** Foydalanuvchiga ko'rinadigan holat va rang (buyurtma holatlari ranglaridan) */
const MINE_STATUS = { NEW: ['Javob kutilmoqda', 'st-NEW'], ANSWERED: ['Javob berildi', 'st-DELIVERED'], CLOSED: ['Yopilgan', ''] };
const STAFF_STATUS = { NEW: ['Yangi', 'st-NEW'], ANSWERED: ['Javob berilgan', 'st-DELIVERED'], CLOSED: ['Yopilgan', ''] };

/** "tel:+998901234567" — havola uchun faqat raqam va "+" */
export const telHref = (p) => 'tel:' + String(p || '').replace(/[^\d+]/g, '');
const topicPill = (t) => `<span class="pill plain">${TOPIC_MARK[t] || '💬'} ${esc(TOPIC[t] || 'Murojaat')}</span>`;
const statusPill = (map, s) => `<span class="pill ${map[s]?.[1] || ''}">${esc(map[s]?.[0] || s)}</span>`;

// =====================================================================
// Foydalanuvchi: xabar yozish va «Mening murojaatlarim»
// =====================================================================
export async function supportPage(main) {
  const cfg = await config();
  main.innerHTML = `<div class="page page-narrow">
    <div class="page-head"><div><h1>Biz bilan bog'lanish</h1>
      <p class="muted">Taklif, muammo yoki savolingiz bo'lsa — yozing. Javob shu sahifada va bildirishnoma bo'lib keladi.</p></div></div>
    <div class="stack">
      ${cfg.supportPhone ? `<section class="panel row-between">
          <div><b>Qo'ng'iroq qilish</b><div class="small muted">Shoshilinch bo'lsa yoki yozish noqulay bo'lsa</div></div>
          <a class="btn btn-primary" href="${esc(telHref(cfg.supportPhone))}">${icon('phone')}${esc(phoneFmt(cfg.supportPhone) || cfg.supportPhone)}</a>
        </section>` : ''}
      <section class="panel">
        <h2>Xabar yozish</h2>
        <form class="stack" id="sup-form" novalidate>
          <div class="kind-chips topic-chips" id="sup-topics" role="radiogroup" aria-label="Murojaat turi">
            ${Object.keys(TOPIC).map((k, i) => `<button type="button" role="radio" data-topic="${k}" class="${i === 0 ? 'on' : ''}" aria-checked="${i === 0}">${TOPIC_MARK[k]} ${esc(TOPIC[k])}</button>`).join('')}
          </div>
          <label class="field"><span>Xabaringiz</span>
            <textarea class="textarea" name="text" maxlength="1000" rows="5" placeholder="${esc(TOPIC_HINT.IDEA)}"></textarea>
            <small>Ismingiz va telefon raqamingiz xabar bilan birga boradi — kerak bo'lsa, sizga qo'ng'iroq qilamiz.</small></label>
          <button class="btn btn-primary btn-lg btn-block" type="submit">Yuborish</button>
        </form>
      </section>
      <section class="panel"><h2>Mening murojaatlarim</h2><div id="sup-mine" style="margin-top:12px">${skeletons(2)}</div></section>
    </div></div>`;

  const form = $('#sup-form', main);
  let topic = 'IDEA';
  $$('#sup-topics button', main).forEach(b => b.addEventListener('click', () => {
    topic = b.dataset.topic;
    $$('#sup-topics button', main).forEach(x => { x.classList.toggle('on', x === b); x.setAttribute('aria-checked', String(x === b)); });
    form.text.placeholder = TOPIC_HINT[topic];
  }));
  form.addEventListener('submit', async (e) => {
    e.preventDefault();
    clearError(form);
    const text = form.text.value.trim();
    if (text.length < 5) return showError(form, { message: 'Xabaringizni batafsilroq yozing' });
    try {
      await busy(form.querySelector('[type=submit]'), () => post('/support', { topic, text }));
      form.text.value = '';
      toast('Yuborildi! Javobni shu yerda va bildirishnomada olasiz', 'ok');
      loadMine();
    } catch (err) { showError(form, err); }
  });

  async function loadMine() {
    const box = $('#sup-mine', main);
    if (!box) return;
    try {
      const items = await get('/support');
      box.innerHTML = items.length ? `<div class="stack-sm">${items.map(m => `<div class="support-item">
          <div class="row-between">${topicPill(m.topic)}<span class="small muted">${esc(ago(m.createdAt))}</span></div>
          <p class="support-text">${esc(m.text)}</p>
          <div>${statusPill(MINE_STATUS, m.status)}</div>
          ${m.reply ? `<div class="support-reply"><div class="row-between"><b>YukGo javobi</b><span class="tiny muted">${esc(ago(m.repliedAt))}</span></div>
            <p class="support-text">${esc(m.reply)}</p></div>` : ''}
        </div>`).join('')}</div>`
        : `<p class="small muted">Hali murojaat yubormagansiz.</p>`;
    } catch (err) { box.innerHTML = `<p class="small muted">${esc(err.message)}</p>`; }
  }
  loadMine();
}

// =====================================================================
// Ega va adminlar: «Haydovchilardan» / «Foydalanuvchilardan»
// =====================================================================

/** Javobsiz (yangi) murojaatlar soni — profil tugmalari va Boshqaruv yorlig'i uchun */
export function supportCounts() {
  return get('/admin/support/counts').catch(() => null);
}

/** Alohida sahifa: #/support/inbox?from=drivers|users (profildagi tugmalar va bildirishnoma havolasi) */
export async function supportInboxPage(main, _p, query) {
  const from = query?.get('from') === 'users' ? 'users' : 'drivers';
  main.innerHTML = `<div class="page page-narrow">
    <div class="page-head"><div><h1>Aloqa xabarlari</h1>
      <p class="muted">«Biz bilan bog'lanish» orqali kelgan taklif, muammo va savollar. Javobingiz ularga bildirishnoma bo'lib boradi.</p></div></div>
    <div id="sx"></div></div>`;
  await supportInbox($('#sx', main), {
    from,
    onFrom: (f) => { try { history.replaceState(history.state, '', '#/support/inbox?from=' + f); } catch { /* */ } },
  });
}

/**
 * Murojaatlar ro'yxati (sahifada ham, Boshqaruv → «Aloqa» yorlig'ida ham).
 * @param {HTMLElement} el
 * @param {{from?: string, onFrom?: (f: string) => void, onCounts?: (c: {drivers: number, users: number}) => void}} opts
 */
export async function supportInbox(el, { from = 'drivers', onFrom, onCounts } = {}) {
  let status = 'NEW', page = 0, items = [], seq = 0;
  el.innerHTML = `<div class="stack">
    <div class="row-between">
      <div class="seg" id="sx-from" role="group" aria-label="Kimdan">
        <button data-from="drivers" class="${from === 'drivers' ? 'on' : ''}">🚚 Haydovchilardan<span class="tab-count hidden" data-count="drivers"></span></button>
        <button data-from="users" class="${from === 'users' ? 'on' : ''}">👤 Foydalanuvchilardan<span class="tab-count hidden" data-count="users"></span></button>
      </div>
      <div class="seg" id="sx-status" role="group" aria-label="Holati">
        <button data-s="NEW" class="on">Yangi</button><button data-s="">Hammasi</button>
      </div>
    </div>
    <div id="sx-list">${skeletons(2)}</div>
    <div class="row" style="justify-content:center"><button class="btn hidden" id="sx-more">Yana ko'rsatish</button></div>
  </div>`;

  $$('#sx-from button', el).forEach(b => b.addEventListener('click', () => {
    from = b.dataset.from;
    $$('#sx-from button', el).forEach(x => x.classList.toggle('on', x === b));
    if (onFrom) onFrom(from);
    reload();
  }));
  $$('#sx-status button', el).forEach(b => b.addEventListener('click', () => {
    status = b.dataset.s;
    $$('#sx-status button', el).forEach(x => x.classList.toggle('on', x === b));
    reload();
  }));
  $('#sx-more', el).addEventListener('click', () => load(true));

  async function refreshCounts() {
    const c = await supportCounts();
    if (!c) return;
    $$('[data-count]', el).forEach(s => {
      const n = c[s.dataset.count] || 0;
      s.textContent = n > 99 ? '99+' : n;
      s.classList.toggle('hidden', !n);
    });
    if (onCounts) onCounts(c);
  }

  function reload() { page = 0; items = []; load(false); refreshCounts(); }

  async function load(more) {
    if (more) page++;
    const my = ++seq;
    const box = $('#sx-list', el);
    if (!box) return;
    if (!more) box.innerHTML = skeletons(2);
    try {
      const data = await get('/admin/support', { from, status, page });
      if (my !== seq) return;
      items = items.concat(data.items);
      box.innerHTML = items.length ? `<div class="stack-sm">${items.map(card).join('')}</div>`
        : emptyState(status === 'NEW' ? "Yangi murojaat yo'q" : "Murojaat yo'q",
          from === 'drivers' ? 'Haydovchilar yozgan taklif va muammolar shu yerda chiqadi.' : 'Foydalanuvchilar yozgan taklif va muammolar shu yerda chiqadi.');
      $('#sx-more', el).classList.toggle('hidden', data.page + 1 >= data.totalPages);
      bind(box);
    } catch (err) {
      if (my !== seq) return;
      box.innerHTML = emptyState("Yuklab bo'lmadi", err.message);
    }
  }

  function card(m) {
    const a = m.author || {};
    const sub = [a.place, a.vehicle].filter(Boolean).join(' · ');
    return `<div class="panel support-item" data-id="${m.id}">
      <div class="row-between" style="align-items:flex-start">
        <a class="person" href="#/u/${a.id}">${avatar(a.avatarUrl, a.fullName)}
          <div style="min-width:0"><div class="person-name">${esc(a.fullName || '')}</div>${sub ? `<div class="person-sub">${esc(sub)}</div>` : ''}</div></a>
        <span class="small muted">${esc(ago(m.createdAt))}</span>
      </div>
      <div class="row" style="gap:8px;margin-top:10px">${topicPill(m.topic)}${statusPill(STAFF_STATUS, m.status)}${a.active === false ? '<span class="pill st-CANCELLED">Hisob bloklangan</span>' : ''}</div>
      <p class="support-text">${esc(m.text)}</p>
      ${m.reply ? `<div class="support-reply"><div class="row-between"><b>Javob${m.repliedByName ? ' · ' + esc(m.repliedByName) : ''}</b>
          <span class="tiny muted">${esc(ago(m.repliedAt))}</span></div><p class="support-text">${esc(m.reply)}</p></div>` : ''}
      <div class="row" style="gap:8px;margin-top:12px">
        <button class="btn btn-primary btn-sm" type="button" data-sx-reply="${m.id}">${icon('chat')}${m.reply ? "Javobni o'zgartirish" : 'Javob yozish'}</button>
        ${a.phone ? `<a class="btn btn-sm" href="${esc(telHref(a.phone))}">${icon('phone')}${esc(phoneFmt(a.phone) || a.phone)}</a>` : ''}
        ${m.status !== 'CLOSED' ? `<button class="btn btn-sm btn-ghost" type="button" data-sx-close="${m.id}">${icon('check')}Yopish</button>` : ''}
      </div>
    </div>`;
  }

  function bind(box) {
    $$('[data-sx-reply]', box).forEach(b => b.addEventListener('click', () => {
      const m = items.find(x => String(x.id) === b.dataset.sxReply);
      if (m) replyModal(m);
    }));
    $$('[data-sx-close]', box).forEach(b => b.addEventListener('click', async () => {
      try {
        await busy(b, () => post(`/admin/support/${b.dataset.sxClose}/close`));
        toast('Yopildi', 'ok');
        reload();
      } catch (err) { toast(err.message, 'err'); }
    }));
  }

  function replyModal(m) {
    modal(m.reply ? "Javobni o'zgartirish" : 'Javob yozish', `<form class="stack" novalidate>
        <div class="panel-sunk small"><b>${esc(m.author?.fullName || '')}:</b> <span style="white-space:pre-line">${esc(m.text)}</span></div>
        <label class="field"><span>Javobingiz</span>
          <textarea class="textarea" name="text" maxlength="1000" rows="5" placeholder="Masalan: Rahmat, taklifingizni keyingi yangilanishda qo'shamiz">${esc(m.reply || '')}</textarea>
          <small>Foydalanuvchiga bildirishnoma (Telegram yoki telefonga) bo'lib boradi va «Biz bilan bog'lanish» sahifasida ko'rinadi.</small></label>
        <button class="btn btn-primary btn-lg btn-block" type="submit">Yuborish</button></form>`, {
      onMount: ({ el: box, close }) => {
        const form = $('form', box);
        form.text.focus();
        form.addEventListener('submit', async (e) => {
          e.preventDefault();
          clearError(form);
          const text = form.text.value.trim();
          if (!text) return showError(form, { message: 'Javob matnini yozing' });
          try {
            await busy(form.querySelector('[type=submit]'), () => post(`/admin/support/${m.id}/reply`, { text }));
            close();
            toast('Javob yuborildi', 'ok');
            reload();
          } catch (err) { showError(form, err); }
        });
      },
    });
  }

  reload();
}
