// YukGo — ilova qobig'i va marshrutlash (hash router)
import { auth, loadMe, get, setUnauthorizedHandler } from './api.js';
import { $, esc, icon, avatar, toast } from './ui.js';
import { loginPage, registerPage, forgotPage } from './pages/auth.js';
import { marketPage, createOrderPage, orderPage, myOrdersPage } from './pages/orders.js';
import { navigationPage } from './pages/navigation.js';
import { profilePage, publicProfilePage, notificationsPage, driversPage } from './pages/profile.js';
import { tripsPage } from './pages/trips.js';
import { adminPage } from './pages/admin.js';
import { tg, initTelegram, telegramRoute, telegramAutoLogin, telegramLogout } from './telegram.js';

const routes = [
  { path: '/', page: marketPage, nav: 'market' },
  { path: '/login', page: loginPage, guest: true },
  { path: '/register', page: registerPage, guest: true },
  { path: '/forgot', page: forgotPage, guest: true },
  { path: '/new', page: createOrderPage, auth: true, nav: 'new' },
  { path: '/orders/:id', page: orderPage, auth: true, nav: 'my' },
  { path: '/nav/:id', page: navigationPage, auth: true, bare: true },
  { path: '/my', page: myOrdersPage, auth: true, nav: 'my' },
  { path: '/trips', page: tripsPage, nav: 'trips' },
  { path: '/drivers', page: driversPage, nav: 'drivers' },
  { path: '/profile', page: profilePage, auth: true, nav: 'profile' },
  { path: '/u/:id', page: publicProfilePage, auth: true },
  { path: '/notifications', page: notificationsPage, auth: true },
  { path: '/admin', page: adminPage, auth: true, staff: true, nav: 'admin' },
];

let cleanup = null;
let unreadTimer = null;
/** Har bir render raqami: sahifa yuklanayotganda boshqasiga o'tilsa, eskisi darhol tozalanadi (GPS kuzatuvi qolib ketmasin) */
let renderSeq = 0;

function match(hash) {
  const path = (hash.replace(/^#/, '') || '/').split('?')[0];
  for (const r of routes) {
    const keys = [];
    const re = new RegExp('^' + r.path.replace(/:(\w+)/g, (_, k) => { keys.push(k); return '([^/]+)'; }) + '/?$');
    const m = path.match(re);
    if (m) {
      const params = {};
      keys.forEach((k, i) => { params[k] = decodeURIComponent(m[i + 1]); });
      const q = new URLSearchParams(hash.split('?')[1] || '');
      return { route: r, params, query: q };
    }
  }
  return null;
}

export function go(hash) {
  if (location.hash === hash) render();
  else location.hash = hash;
}

/** Tarixga yangi qadam qo'shmasdan o'tish (yo'naltirishlar uchun: "Orqaga" bosilganda yana shu yerga qaytib kelmasin) */
export function redirect(hash) {
  history.replaceState(history.state, '', hash);
  return render();
}

async function render() {
  const seq = ++renderSeq;
  if (cleanup) { try { cleanup(); } catch { /* */ } cleanup = null; }
  telegramRoute((location.hash.replace(/^#/, '') || '/').split('?')[0]);
  const main = $('#main');
  const m = match(location.hash || '#/');
  if (!m) {
    main.innerHTML = `<div class="page">${'<div class="empty"><h3>Sahifa topilmadi</h3><a class="btn" href="#/">Bosh sahifaga qaytish</a></div>'}</div>`;
    return;
  }
  const { route, params, query } = m;
  if (route.auth && !auth.loggedIn) {
    sessionStorageSafe('yukgo_after_login', location.hash);
    return redirect('#/login');
  }
  if (route.guest && auth.loggedIn) return redirect('#/');
  if (route.staff && !auth.isStaff) return redirect('#/');

  document.body.classList.toggle('bare', !!route.bare);
  $('#topbar').classList.toggle('hidden', !!route.bare);
  $('#tabbar').classList.toggle('hidden', !!route.bare);
  renderShell(route.nav);
  main.innerHTML = '';
  window.scrollTo(0, 0);
  try {
    const c = await route.page(main, params, query) || null;
    if (seq !== renderSeq) {   // yuklanayotganda boshqa sahifaga o'tildi
      if (typeof c === 'function') { try { c(); } catch { /* */ } }
      return;
    }
    cleanup = c;
  } catch (e) {
    if (seq !== renderSeq) return;
    console.error(e);
    main.innerHTML = `<div class="page"><div class="empty"><h3>Sahifani ochib bo'lmadi</h3><p class="muted">${esc(e.message || e)}</p><a class="btn" href="#/">Bosh sahifa</a></div></div>`;
  }
  main.focus({ preventScroll: true });
}

function renderShell(active) {
  const u = auth.user;
  const links = [
    ['market', '#/', 'Buyurtmalar', 'market'],
    ['trips', '#/trips', 'Reyslar', 'route'],
    ['drivers', '#/drivers', 'Haydovchilar', 'truck'],
  ];
  if (u) links.push(['my', '#/my', 'Buyurtmalarim', 'box']);
  if (auth.isStaff) links.push(['admin', '#/admin', 'Boshqaruv', 'shield']);
  $('#topnav').innerHTML = links.map(([k, href, label]) =>
    `<a href="${href}" class="${k === active ? 'active' : ''}">${esc(label)}</a>`).join('');

  $('#topbar-right').innerHTML = u
    ? `<a class="btn btn-primary btn-sm" href="#/new">${icon('plus')}<span>Buyurtma berish</span></a>
       <a class="icon-btn" href="#/notifications" aria-label="Bildirishnomalar">${icon('bell')}<span class="badge-dot hidden" id="unread"></span></a>
       <a class="me-chip" href="#/profile">${avatar(u.avatarUrl, u.fullName)}<span>${esc(u.firstName)}</span></a>`
    : `<a class="btn btn-sm" style="background:transparent;color:#fff;border-color:rgba(255,255,255,.3)" href="#/login">Kirish</a>
       <a class="btn btn-primary btn-sm" href="#/register">Ro'yxatdan o'tish</a>`;
  // mobil ekranda "Buyurtma berish" matni sig'maydi
  if (window.matchMedia('(max-width: 760px)').matches) {
    const nb = $('#topbar-right .btn-primary span');
    if (nb && u) nb.parentElement.remove();
  }

  const tabs = [
    ['market', '#/', 'Bozor', 'market'],
    ['trips', '#/trips', 'Reyslar', 'route'],
    ['new', '#/new', 'Yangi', 'plus'],
    ['my', '#/my', 'Buyurtmalar', 'box'],
    [auth.isStaff ? 'admin' : 'profile', auth.isStaff ? '#/admin' : '#/profile', auth.isStaff ? 'Boshqaruv' : 'Profil', auth.isStaff ? 'shield' : 'user'],
  ];
  $('#tabbar').innerHTML = tabs.map(([k, href, label, ic]) => k === 'new'
    ? `<a href="${href}" class="plus ${k === active ? 'active' : ''}"><span class="plus-disc">${icon(ic)}</span>${esc(label)}</a>`
    : `<a href="${href}" class="${k === active ? 'active' : ''}">${icon(ic)}${esc(label)}</a>`).join('');
  refreshUnread();
}

export async function refreshUnread() {
  if (!auth.loggedIn) return;
  try {
    const { count } = await get('/notifications/unread-count');
    const el = $('#unread');
    if (el) { el.textContent = count > 99 ? '99+' : count; el.classList.toggle('hidden', !count); }
  } catch { /* */ }
}

export async function refreshMe() {
  await loadMe();
  renderShell(match(location.hash || '#/')?.route.nav);
}

function sessionStorageSafe(k, v) {
  try { if (v === undefined) { const x = sessionStorage.getItem(k); sessionStorage.removeItem(k); return x; } sessionStorage.setItem(k, v); } catch { return null; }
}

export function afterLogin() {
  const back = sessionStorageSafe('yukgo_after_login');
  redirect(back && !back.includes('login') ? back : '#/');
}

/**
 * @param {{server?: boolean}} opts server=false — hisob o'chirilgandan keyin (serverga so'rov yubormaymiz)
 */
export async function logout({ server = true } = {}) {
  if (server) await telegramLogout();   // Telegram ichida: keyingi ochilishda o'zi kirib qolmasin
  auth.token = null;
  auth.user = null;
  redirect('#/login');
}

setUnauthorizedHandler(async (msg) => {
  // Telegram ichida sessiya tugagan bo'lsa — parolsiz qayta kirib ko'ramiz
  if (await telegramAutoLogin()) {
    render();
    return;
  }
  toast(msg, 'err');
  redirect('#/login');
});

/**
 * Android ilova (Capacitor): telefonning "Orqaga" tugmasi — avval ochiq oynani yopadi, keyin sahifalar bo'yicha orqaga
 * qaytadi, birinchi sahifada esa ilovani yig'ib qo'yadi (qayta ochilganda shu joydan davom etadi).
 */
function nativeBackButton() {
  let App = null;
  try {
    const cap = window.Capacitor;
    App = cap && cap.isNativePlatform && cap.isNativePlatform() && cap.Plugins ? cap.Plugins.App : null;
  } catch { /* */ }
  if (!App || typeof App.addListener !== 'function') return;
  try {
    App.addListener('backButton', (ev) => {
      const closers = document.querySelectorAll('#modal-root [data-close]');
      if (closers.length) { closers[closers.length - 1].click(); return; }
      if (ev && ev.canGoBack) history.back();
      else App.minimizeApp?.();
    });
  } catch { /* eski ilova versiyasi — Android o'zi boshqaradi */ }
}

(async function boot() {
  nativeBackButton();
  await initTelegram();   // oddiy brauzerda hech narsa qilmaydi
  await loadMe({ silent: true });
  if (tg.active && !auth.user) await telegramAutoLogin();   // Telegram ichida: hisob ulangan bo'lsa parolsiz kiradi
  window.addEventListener('hashchange', render);
  await render();
  unreadTimer = setInterval(refreshUnread, 30000);
  void unreadTimer;
})();
