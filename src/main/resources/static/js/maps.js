// Yandex Maps JS API 2.1 — sayt ichida ishlaydi, foydalanuvchi Yandex ilovasiga o'tib ketmaydi.
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
    suppressMapOpenBlock: true,    // "Yandex Xaritada ochish" tugmasini yashiramiz
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

/** Marshrut chizadi va masofa/vaqtni qaytaradi */
export function drawRoute(ymaps, map, points, { fit = true } = {}) {
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
      if (!r) return resolve({ route: mr, distanceKm: null, durationMin: null });
      const dist = r.properties.get('distance');
      const dur = r.properties.get('durationInTraffic') || r.properties.get('duration');
      resolve({
        route: mr,
        distanceKm: dist ? Math.round(dist.value / 100) / 10 : null,
        durationMin: dur ? Math.round(dur.value / 60) : null,
      });
    });
    mr.model.events.add('requestfail', () => resolve({ route: mr, distanceKm: null, durationMin: null, failed: true }));
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

export function myPosition({ highAccuracy = true, timeout = 10000 } = {}) {
  return new Promise((resolve, reject) => {
    if (!navigator.geolocation) return reject(new Error("Qurilmangiz joylashuvni aniqlay olmaydi"));
    navigator.geolocation.getCurrentPosition(
      (p) => resolve([p.coords.latitude, p.coords.longitude]),
      (e) => reject(new Error(e.code === 1 ? "Joylashuvga ruxsat berilmagan. Brauzer sozlamalaridan ruxsat bering" : "Joylashuvni aniqlab bo'lmadi")),
      { enableHighAccuracy: highAccuracy, timeout, maximumAge: 15000 });
  });
}
