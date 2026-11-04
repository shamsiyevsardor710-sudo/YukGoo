// Ulashish: Telegram ichida — Telegramning o'z «Ulashish» oynasi, telefonda — tizimning ulashish oynasi,
// boshqa joyda — havola nusxalanadi (bo'lmasa — havola ko'rsatiladi)
import { tg } from './telegram.js';
import { esc, icon, modal, toast, copyText } from './ui.js';

/** Sayt havolasi: "#/orders/5" → "https://sayt/#/orders/5" */
export function siteLink(hash) {
  return location.origin + '/' + hash;
}

/** Telegramda ulashish havolasi (do'st yoki guruh tanlanadi) */
export function telegramShareUrl(url, text) {
  return `https://t.me/share/url?url=${encodeURIComponent(url)}&text=${encodeURIComponent(text)}`;
}

/**
 * @param {{title: string, text: string, url: string, tgUrl?: string}} p
 *   tgUrl — Telegram ichida ulashiladigan havola (masalan, bot havolasi: ochganda e'lon kartasi chiqadi)
 * @returns {Promise<boolean>} ulashildi (yoki nusxalandi)
 */
export async function share({ title, text, url, tgUrl }) {
  if (tg.active && tg.app && tg.app.openTelegramLink) {
    try {
      tg.app.openTelegramLink(telegramShareUrl(tgUrl || url, text));
      return true;
    } catch { /* quyidagi usullar */ }
  }
  if (navigator.share) {
    try {
      await navigator.share({ title, text, url });
      return true;
    } catch (e) {
      if (e && e.name === 'AbortError') return false;   // foydalanuvchi o'zi yopdi
    }
  }
  if (await copyText(`${text}\n${url}`)) {
    toast("Havola nusxalandi — do'stlaringizga yoki guruhga yuboring", 'ok');
    return true;
  }
  modal('Ulashish', `<div class="stack">
      <p class="small muted">Havolani nusxalab, do'stlaringizga yoki guruhga yuboring:</p>
      <div class="link-box"><code>${esc(url)}</code></div>
      <a class="btn btn-route" href="${esc(telegramShareUrl(tgUrl || url, text))}" target="_blank" rel="noopener">${icon('telegram')}Telegramda ulashish</a>
    </div>`);
  return false;
}
