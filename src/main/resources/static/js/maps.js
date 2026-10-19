// Yandex Maps JS API 2.1 — xarita sayt ichida ishlaydi.
// Bepul kalit shartlari: Yandex logotipi, mualliflik yozuvi va «Открыть в Яндекс Картах» tugmasi
// doim ko'rinib turishi shart, shuning uchun ularni yashirmaymiz.
// Marshrut va manzil aniqlash so'rovlari kunlik limitdan ketadi — ular tejab ishlatiladi (pastga qarang).
import { config } from './api.js';
import { esc } from './ui.js';

let ymapsPromise;

export function loadYmaps() {
  return ymapsPromise ??= (async () => {
    const cfg = await config();
    await new Promise((resolve, reject) => {
      const s = document.createElement('script');
      const key = cfg.yandexMapsApiKey ? `apikey=${encodeURIComponent(cfg.yandexMapsApiKey)}&` : '';
      s.src = `https://api-maps.yandex.ru/2.1/?${key}lang=ru_RU&load=package.full`;
      s.async = true;
      s.onload = resolve;
      s.onerror = () => reject(new Error("Xarita yuklanmadi"));
      document.head.appendChild(s);
      setTimeout(() => reject(new Error('Xarita yuklanishi juda uzoq davom etdi')), 20000);
    });
    await new Promise((r) => window.ymaps.ready(r));
    return window.ymaps;
  })().catch((e) => { ymapsPromise = null; throw e; });
}

export const UZ_CENTER = [41.3111, 69.2797];

export async function createMap(el, { center = UZ_CENTER, zoom = 6, controls } = {}) {
  let ymaps;
  try {
    ymaps = await loadYmaps();
  } catch (e) {
    el.innerHTML = `<div class="map-empty">Xaritani yuklab bo'lmadi. Internetni tekshiring yoki administrator Yandex Maps API kalitini sozlashi kerak.<br><small>${esc(e.message)}</small></div>`;
    throw e;
  }
  el.innerHTML = '';
  const map = new ymaps.Map(el, {
    center, zoom,
    controls: controls ?? ['zoomControl', 'geolocationControl', 'trafficControl', 'typeSelector'],
  }, {
    yandexMapDisablePoiInteractivity: true,
  });
  map.behaviors.disable('scrollZoom');
  return { ymaps, map };
}

const PRESETS = {
  from: { preset: 'islands#yellowStretchyIcon', iconColor: '#F2B705' },
  to: { preset: 'islands#darkBlueStretchyIcon', iconColor: '#1F2A30' },
  driver: { preset: 'islands#blueAutoIcon', iconColor: '#1D5FA8' },
  me: { preset: 'islands#geolocationIcon', iconColor: '#1D5FA8' },
  dot: { preset: 'islands#yellowCircleDotIcon' },
};

export function pin(ymaps, coords, kind = 'from', { label, hint, draggable = false } = {}) {
  return new ymaps.Placemark(coords, {
    iconContent: label || '',
    hintContent: hint || '',
  }, { ...PRESETS[kind], draggable });
}

// Bir xil nuqtalar uchun marshrut qayta so'ralmasin (sahifa qayta chizilganda ham)
const routeCache = new Map();
const ROUTE_CACHE_MAX = 50;
const routeKey = (points) => points.map((p) => `${p[0].toFixed(5)},${p[1].toFixed(5)}`).join('|');

/**
 * Marshrut chizadi. Natija: { route, distanceKm, durationMin, coords, failed? }
 * coords — marshrut chizig'i nuqtalari [[lat, lng], ...] (navigatsiyada qolgan masofani hisoblash uchun).
 * useCache: false — keshdan foydalanmaslik (haydovchining joylashuvi har safar boshqa bo'ladi).
 */
export function drawRoute(ymaps, map, points, { fit = true, useCache = true } = {}) {
  const key = routeKey(points);
  const cached = useCache ? routeCache.get(key) : null;
  if (cached) {
    const line = new ymaps.Polyline(cached.coords, {}, { strokeColor: '#1D5FA8', strokeWidth: 6, strokeOpacity: 0.9 });
    map.geoObjects.add(line);
    if (fit) map.setBounds(line.geometry.getBounds(), { checkZoomRange: true, zoomMargin: 40 });
    return Promise.resolve({ route: line, ...cached });
  }
  return new Promise((resolve) => {
    const mr = new ymaps.multiRouter.MultiRoute({
      referencePoints: points,
      params: { routingMode: 'auto', results: 1 },
    }, {
      boundsAutoApply: fit,
      wayPointVisible: false,
      routeActiveStrokeWidth: 6,
      routeActiveStrokeColor: '#1D5FA8',
      routeStrokeColor: '#9BB7D6',
      pinVisible: false,
    });
    mr.model.events.add('requestsuccess', () => {
      const r = mr.getActiveRoute();
      if (!r) return resolve({ route: mr, distanceKm: null, durationMin: null, coords: [] });
      const dist = r.properties.get('distance');
      const dur = r.properties.get('durationInTraffic') || r.properties.get('duration');
      const coords = [];
      try {
        r.getPaths().each((path) => {
          for (const c of path.geometry.getCoordinates()) coords.push(c);
        });
      } catch { /* chiziq nuqtalarisiz ham ishlayveradi */ }
      const result = {
        distanceKm: dist ? Math.round(dist.value / 100) / 10 : null,
        durationMin: dur ? Math.round(dur.value / 60) : null,
        coords,
      };
      if (useCache && coords.length > 1) {
        if (routeCache.size >= ROUTE_CACHE_MAX) routeCache.delete(routeCache.keys().next().value);
        routeCache.set(key, result);
      }
      resolve({ route: mr, ...result });
    });
    mr.model.events.add('requestfail', () => resolve({ route: mr, distanceKm: null, durationMin: null, coords: [], failed: true }));
    map.geoObjects.add(mr);
  });
}

export async function reverseGeocode(ymaps, coords) {
  try {
    const res = await ymaps.geocode(coords, { results: 1 });
    const g = res.geoObjects.get(0);
    return g ? g.getAddressLine() : '';
  } catch { return ''; }
}

export async function geocode(ymaps, text) {
  try {
    const res = await ymaps.geocode(text, { results: 1, boundedBy: [[37, 55.9], [45.6, 73.2]], strictBounds: false });
    const g = res.geoObjects.get(0);
    return g ? g.geometry.getCoordinates() : null;
  } catch { return null; }
}

/** Ikki nuqta orasidagi masofa (metr) */
export function haversine(a, b) {
  const R = 6371000, toRad = (d) => d * Math.PI / 180;
  const dLat = toRad(b[0] - a[0]), dLng = toRad(b[1] - a[1]);
  const x = Math.sin(dLat / 2) ** 2 + Math.cos(toRad(a[0])) * Math.cos(toRad(b[0])) * Math.sin(dLng / 2) ** 2;
  return 2 * R * Math.asin(Math.sqrt(x));
}

/** Marshrut chizig'ini navigatsiya uchun tayyorlaydi: har bir nuqtadan manzilgacha masofa oldindan hisoblanadi. */
export function prepareRouteLine(coords) {
  if (!coords || coords.length < 2) return null;
  const toEnd = new Array(coords.length).fill(0);
  for (let i = coords.length - 2; i >= 0; i--) toEnd[i] = toEnd[i + 1] + haversine(coords[i], coords[i + 1]);
  return { coords, toEnd, totalM: toEnd[0] };
}

/**
 * Nuqta marshrut chizig'idan qancha uzoqda (offM) va marshrut bo'yicha manzilgacha qancha qolgani (restM), metrda.
 * Har bir GPS yangilanishida qurilmaning o'zida hisoblanadi — Yandex'ga so'rov ketmaydi.
 */
export function locateOnRoute(line, p) {
  const rad = Math.PI / 180, R = 6371000;
  const kx = Math.cos(p[0] * rad) * R * rad, ky = R * rad;   // gradusdan metrga (nuqta atrofida)
  let best = { offM: Infinity, restM: line.totalM };
  for (let i = 0; i < line.coords.length - 1; i++) {
    const a = line.coords[i], b = line.coords[i + 1];
    const ax = (a[1] - p[1]) * kx, ay = (a[0] - p[0]) * ky;
    const dx = (b[1] - a[1]) * kx, dy = (b[0] - a[0]) * ky;
    const len2 = dx * dx + dy * dy;
    const t = len2 ? Math.max(0, Math.min(1, -(ax * dx + ay * dy) / len2)) : 0;
    const off = Math.hypot(ax + t * dx, ay + t * dy);
    if (off < best.offM) best = { offM: off, restM: (1 - t) * Math.sqrt(len2) + line.toEnd[i + 1] };
  }
  return best;
}

export function myPosition({ highAccuracy = true, timeout = 10000 } = {}) {
  return new Promise((resolve, reject) => {
    if (!navigator.geolocation) return reject(new Error("Qurilmangiz joylashuvni aniqlay olmaydi"));
    navigator.geolocation.getCurrentPosition(
      (p) => resolve([p.coords.latitude, p.coords.longitude]),
      (e) => reject(new Error(e.code === 1 ? "Joylashuvga ruxsat berilmagan. Brauzer sozlamalaridan ruxsat bering" : "Joylashuvni aniqlab bo'lmadi")),
      { enableHighAccuracy: highAccuracy, timeout, maximumAge: 15000 });
  });
}
