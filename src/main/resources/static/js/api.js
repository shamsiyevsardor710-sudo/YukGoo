// YukGo — backend bilan ishlash (REST + JWT)
const TOKEN_KEY = 'yukgo_token';

function storage(fn) {
  try { return fn(window.localStorage); } catch { return null; }
}

export const auth = {
  get token() { return storage(s => s.getItem(TOKEN_KEY)); },
  set token(v) { storage(s => v ? s.setItem(TOKEN_KEY, v) : s.removeItem(TOKEN_KEY)); },
  user: null,
  get loggedIn() { return !!this.token; },
  get isStaff() { return this.user && (this.user.role === 'ADMIN' || this.user.role === 'OWNER'); },
  get isOwner() { return this.user && this.user.role === 'OWNER'; },
};

export class ApiError extends Error {
  constructor(status, message, data) {
    super(message);
    this.status = status;
    this.data = data;
  }
}

let onUnauthorized = () => {};
export function setUnauthorizedHandler(fn) { onUnauthorized = fn; }

/** silent — 401/403 da kirish sahifasiga o'tkazmaydi (ilova ishga tushayotganda: Telegram ichida avval avtomatik kirib ko'ramiz) */
export async function api(path, { method = 'GET', body, form, query, silent = false } = {}) {
  let url = '/api' + path;
  if (query) {
    const qs = new URLSearchParams();
    for (const [k, v] of Object.entries(query)) {
      if (v !== undefined && v !== null && v !== '') qs.set(k, v);
    }
    const s = qs.toString();
    if (s) url += (url.includes('?') ? '&' : '?') + s;
  }
  const headers = {};
  if (auth.token) headers.Authorization = 'Bearer ' + auth.token;
  let payload;
  if (form) {
    payload = form;
  } else if (body !== undefined) {
    headers['Content-Type'] = 'application/json';
    payload = JSON.stringify(body);
  }
  let res;
  try {
    res = await fetch(url, { method, headers, body: payload });
  } catch {
    throw new ApiError(0, "Internet aloqasi yo'q. Qayta urinib ko'ring");
  }
  const text = await res.text();
  let data = null;
  if (text) { try { data = JSON.parse(text); } catch { data = text; } }
  if (!res.ok) {
    const msg = (data && data.message) || 'Xatolik yuz berdi (' + res.status + ')';
    if (res.status === 401 && auth.token) {
      auth.token = null;
      auth.user = null;
      if (!silent) onUnauthorized(msg);
    }
    if (res.status === 403 && data && data.code && data.code.startsWith('ACCOUNT_')) {
      auth.token = null;
      auth.user = null;
      if (!silent) onUnauthorized(msg);
    }
    throw new ApiError(res.status, msg, data);
  }
  return data;
}

export const get = (p, query) => api(p, { query });
export const post = (p, body) => api(p, { method: 'POST', body: body ?? {} });
export const put = (p, body) => api(p, { method: 'PUT', body });
export const del = (p) => api(p, { method: 'DELETE' });
export const upload = (p, file) => {
  const fd = new FormData();
  fd.append('file', file);
  return api(p, { method: 'POST', form: fd });
};

// ---- Kesh: sozlamalar va viloyatlar ----
let configPromise, regionsPromise;
const districtCache = new Map();

export function config() {
  return configPromise ??= get('/config').catch(() => ({ yandexMapsApiKey: '', botUsername: 'yukgo_bot' }));
}
export function regions() {
  return regionsPromise ??= get('/locations/regions');
}
export function districts(regionId) {
  if (!regionId) return Promise.resolve([]);
  if (!districtCache.has(regionId)) districtCache.set(regionId, get(`/locations/regions/${regionId}/districts`));
  return districtCache.get(regionId);
}

export async function loadMe({ silent = false } = {}) {
  if (!auth.token) { auth.user = null; return null; }
  try {
    auth.user = await api('/users/me', { silent });
  } catch (e) {
    if (e.status === 401 || e.status === 403) { auth.token = null; auth.user = null; }
  }
  return auth.user;
}
