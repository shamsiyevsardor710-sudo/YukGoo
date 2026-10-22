// Profildagi ikki blok: "Do'stlarni taklif qiling" (shaxsiy havola) va "Tekshirilgan haydovchi" (hujjatlarni yuborish)
import { api, get, config } from '../api.js';
import { $, $$, esc, icon, money, num, ago, date, toast, busy, copyText } from '../ui.js';

// =====================================================================
// Taklif havolasi
// =====================================================================
export async function inviteBlock(el) {
  let r;
  try { r = await get('/users/me/referral'); } catch { el.innerHTML = ''; return; }
  const cfg = await config().catch(() => ({}));
  const bonusOn = !!cfg.pricing?.enabled && r.bonus > 0;
  const shareText = "YukGo — yuk egalari va haydovchilar uchun: yuk e'lon qiling yoki buyurtma toping. Ro'yxatdan o'ting:";
  const tgShare = `https://t.me/share/url?url=${encodeURIComponent(r.botLink)}&text=${encodeURIComponent(shareText)}`;
  el.innerHTML = `<section class="panel stack-sm">
    <h2>Do'stlarni taklif qiling</h2>
    <p class="small muted">${bonusOn
      ? `Havolangiz orqali kelgan do'stingiz birinchi buyurtmasini yakunlasa — ikkalangizga <b>${esc(money(r.bonus)).replace(/ /g, '&nbsp;')}</b> bonus balansga tushadi.`
      : "Havolangizni haydovchi va yuk egasi tanishlaringizga yuboring — ular sizning taklifingiz bilan ro'yxatdan o'tadi."}</p>
    <div class="link-box"><code>${esc(r.botLink)}</code><button class="btn btn-sm" type="button" data-copy="${esc(r.botLink)}">${icon('copy')}Nusxa</button></div>
    <div class="row">
      <a class="btn btn-route btn-sm" href="${esc(tgShare)}" target="_blank" rel="noopener">${icon('telegram')}Telegramda ulashish</a>
      <button class="btn btn-sm" type="button" data-copy="${esc(r.siteLink)}">${icon('copy')}Sayt havolasi</button>
    </div>
    <div class="stat-row">
      <div><b>${num(r.invited)}</b><span>Ro'yxatdan o'tdi</span></div>
      <div><b>${num(r.rewarded)}</b><span>Buyurtma yakunladi</span></div>
      <div><b>${bonusOn ? esc(num(r.bonus)) : '—'}</b><span>${bonusOn ? "so'm bonus har biri uchun" : 'Bonus'}</span></div>
    </div>
  </section>`;
  $$('[data-copy]', el).forEach(b => b.addEventListener('click', async () => {
    toast(await copyText(b.dataset.copy) ? 'Havola nusxalandi' : "Nusxalab bo'lmadi — havolani bosib turib nusxalang", 'ok');
  }));
}

// =====================================================================
// "Tekshirilgan haydovchi"
// =====================================================================
const MAX_SIDE = 1600;

function loadImage(file) {
  return new Promise((resolve, reject) => {
    const url = URL.createObjectURL(file);
    const img = new Image();
    img.onload = () => { URL.revokeObjectURL(url); resolve(img); };
    img.onerror = () => { URL.revokeObjectURL(url); reject(new Error('decode')); };
    img.src = url;
  });
}

/**
 * Telefon kamerasidagi 5–10 MB rasmni ~0.3–0.8 MB JPEG ga kichraytiradi (hujjat o'qilishi uchun 1600px yetarli).
 * Brauzer ocha olmasa (masalan, HEIC) — kichik JPG/PNG/WEBP bo'lsa o'zi yuboriladi, aks holda xato.
 */
export async function compressImage(file) {
  if (!file) throw new Error('Rasm tanlang');
  let img;
  try {
    img = await loadImage(file);
  } catch {
    if (/^image\/(jpeg|png|webp)$/.test(file.type) && file.size <= 5 * 1024 * 1024) return file;
    throw new Error("Rasmni ochib bo'lmadi — JPG yoki PNG formatdagi rasm tanlang");
  }
  const scale = Math.min(1, MAX_SIDE / Math.max(img.naturalWidth || img.width, img.naturalHeight || img.height));
  const w = Math.max(1, Math.round((img.naturalWidth || img.width) * scale));
  const h = Math.max(1, Math.round((img.naturalHeight || img.height) * scale));
  const canvas = document.createElement('canvas');
  canvas.width = w;
  canvas.height = h;
  const ctx = canvas.getContext('2d');
  ctx.fillStyle = '#fff';   // shaffof PNG qora bo'lib qolmasin
  ctx.fillRect(0, 0, w, h);
  ctx.drawImage(img, 0, 0, w, h);
  const blob = await new Promise(res => canvas.toBlob(res, 'image/jpeg', 0.85));
  if (!blob) return file;
  return new File([blob], 'hujjat.jpg', { type: 'image/jpeg' });
}

export async function verifyBlock(el, user, { onChange } = {}) {
  if (!user?.driver) { el.innerHTML = ''; return; }
  let st;
  try { st = await get('/users/me/driver-verification'); } catch { el.innerHTML = ''; return; }
  const head = `<h2>Tekshirilgan haydovchi</h2>`;
  if (st.verified) {
    el.innerHTML = `<section class="panel stack-sm">${head}
      <div class="ver-state ok">${icon('verified')}<div><b>Siz tekshirilgan haydovchisiz</b><br>
        Profilingiz, takliflaringiz va haydovchilar ro'yxatida belgi ko'rinadi.${st.decidedAt ? ` Tasdiqlangan: ${esc(date(st.decidedAt))}.` : ''}</div></div>
      <p class="tiny muted">Davlat raqamini o'zgartirsangiz yoki mashinani o'chirsangiz, belgi qayta tekshiruvgacha olinadi.</p>
    </section>`;
    return;
  }
  const pending = st.status === 'PENDING';
  const rejected = st.status === 'REJECTED';
  el.innerHTML = `<section class="panel stack-sm">${head}
    ${pending ? `<div class="ver-state wait">${icon('file')}<div><b>Hujjatlaringiz ko'rib chiqilmoqda</b><br>
      Natija bildirishnoma va Telegram orqali keladi.${st.submittedAt ? ` Yuborilgan: ${esc(ago(st.submittedAt))}.` : ''}</div></div>` : ''}
    ${rejected ? `<div class="ver-state bad">${icon('close')}<div><b>Tekshiruvdan o'tmadingiz</b>${st.rejectReason ? `<br>Sabab: ${esc(st.rejectReason)}` : ''}
      <br>Aniq va to'liq rasm bilan qaytadan yuboring.</div></div>` : ''}
    ${!pending && !rejected ? `<p class="small">Haydovchilik guvohnomangiz va texnik pasport rasmini yuboring — admin tekshirgach, profilingizda
      <span class="vbadge">${icon('verified')}<span>Tekshirilgan</span></span> belgisi paydo bo'ladi. Yuk egalari belgili haydovchiga
      ko'proq ishonadi, haydovchilar ro'yxatida ham yuqorida turasiz.</p>` : ''}
    <form class="stack-sm${pending ? ' hidden' : ''}" id="ver-form" novalidate>
      <div class="doc-pick">
        <label class="doc-slot" data-kind="license"><b>Haydovchilik guvohnomasi</b><span>Hamma yozuvi o'qiladigan, yaltiramagan rasm</span>
          <input type="file" accept="image/*"></label>
        <label class="doc-slot" data-kind="techPassport"><b>Texnik pasport</b><span>Davlat raqami ko'rinadigan tomoni</span>
          <input type="file" accept="image/*"></label>
      </div>
      <p class="tiny muted">🔒 Rasmlarni faqat YukGo adminlari ko'radi, tekshiruvdan keyin darhol o'chiriladi. Hujjat ma'lumotlari hech kimga ko'rsatilmaydi.</p>
      <button class="btn btn-primary" type="submit">Tekshiruvga yuborish</button>
    </form>
    ${pending ? `<button class="link-btn" type="button" id="ver-again" style="justify-self:start">Boshqa rasm yuborish</button>` : ''}
  </section>`;

  const form = $('#ver-form', el);
  const chosen = {};
  $('#ver-again', el)?.addEventListener('click', (e) => { form.classList.remove('hidden'); e.target.remove(); });
  $$('.doc-slot', el).forEach(slot => {
    const input = $('input', slot);
    input.addEventListener('change', async () => {
      const f = input.files[0];
      if (!f) return;
      try {
        const small = await compressImage(f);
        chosen[slot.dataset.kind] = small;
        let img = $('img', slot);
        if (!img) { img = document.createElement('img'); img.alt = ''; slot.insertBefore(img, slot.firstChild); }
        if (img.dataset.url) URL.revokeObjectURL(img.dataset.url);
        img.dataset.url = URL.createObjectURL(small);
        img.src = img.dataset.url;
        slot.classList.add('has');
      } catch (err) {
        input.value = '';
        toast(err.message, 'err');
      }
    });
  });
  form.addEventListener('submit', async (e) => {
    e.preventDefault();
    if (!chosen.license || !chosen.techPassport) return toast('Ikkala hujjat rasmini tanlang', 'err');
    const fd = new FormData();
    fd.append('license', chosen.license);
    fd.append('techPassport', chosen.techPassport);
    try {
      await busy(form.querySelector('[type=submit]'), () => api('/users/me/driver-verification', { method: 'POST', form: fd }));
      toast('Yuborildi! Natija Telegram orqali keladi', 'ok');
      $$('img', el).forEach(i => i.dataset.url && URL.revokeObjectURL(i.dataset.url));
      verifyBlock(el, user, { onChange });
      onChange?.();
    } catch (err) { toast(err.message, 'err'); }
  });
}
