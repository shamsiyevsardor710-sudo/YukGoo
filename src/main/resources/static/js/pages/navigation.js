// Haydovchi navigatsiyasi: ilova ichida Yandex xarita, jonli joylashuv, marshrut va yuboruvchiga GPS uzatish
import { auth, get, post } from '../api.js';
import { $, esc, icon, toast, km, phoneFmt, modal, showError, busy, confirmDialog } from '../ui.js';
import { createMap, pin, drawRoute, geocode, haversine, myPosition, prepareRouteLine, locateOnRoute } from '../maps.js';
import { go } from '../app.js';

// Android ilova (Capacitor) ichida: GPS ekran o'chiq bo'lsa ham ishlaydi (fon xizmati va bildirishnoma bilan),
// nuqtalar esa telefonning o'z HTTP'si orqali yuboriladi (WebView fonda 5 daqiqadan keyin so'rovlarni sekinlashtiradi).
function nativeApp() {
  const cap = window.Capacitor;
  try {
    return cap && cap.isNativePlatform && cap.isNativePlatform() && cap.Plugins && cap.Plugins.BackgroundGeolocation ? cap : null;
  } catch { return null; }
}

/**
 * Joylashuv ruxsati. Android ilovada WebView'ning joylashuv so'rovi tizimning ruxsat oynasini ochadi (Capacitor shunday
 * ulagan). Fon xizmatini ruxsat berilgandan KEYIN ishga tushirish kerak — aks holda Android uni rad etadi va kuzatuv
 * ekran o'chganda to'xtab qoladi.
 * @returns {Promise<'granted'|'denied'|'unavailable'>}
 */
function locationPermission() {
  return new Promise((resolve) => {
    if (!navigator.geolocation) return resolve('unavailable');
    navigator.geolocation.getCurrentPosition(
      () => resolve('granted'),
      (e) => resolve(e && e.code === 1 ? 'denied' : 'unavailable'),
      { enableHighAccuracy: false, timeout: 20000, maximumAge: 10 * 60 * 1000 });
  });
}

/** Joylashuv haqidagi ogohlantirish bir marta ko'rsatiladi (rozilik shu qurilmada eslab qolinadi) */
const DISCLOSURE_KEY = 'yukgo_location_disclosure';
function disclosureAccepted() {
  try { return localStorage.getItem(DISCLOSURE_KEY) === '1'; } catch { return false; }
}
function rememberDisclosure() {
  try { localStorage.setItem(DISCLOSURE_KEY, '1'); } catch { /* xotira yopiq — keyingi safar yana so'raladi */ }
}

/**
 * Google Play talabi: tizimning ruxsat oynasidan OLDIN ilovaning o'zida joylashuv nima uchun, qachon (shu jumladan
 * ilova yopiq yoki ekran o'chiq bo'lganda) yig'ilishi va kimga ko'rinishini aytish, foydalanuvchi rozilik bersin.
 * @returns {Promise<boolean>} true — «Roziman»
 */
function locationDisclosure() {
  return new Promise((resolve) => {
    let done = false;
    modal('Joylashuvdan foydalanish', `<div class="stack">
        <p><b>YukGo bu buyurtmani yetkazayotganingizda joylashuvingizni (GPS) yig'adi</b> va yuk qayerdaligini yuboruvchiga xaritada
          ko'rsatish uchun serverga yuboradi — <b>ilova yopiq yoki ekran o'chiq bo'lsa ham</b>.</p>
        <ul class="small disclosure-list">
          <li>Kuzatuv faqat navigatsiya ochilganda boshlanadi. Shu vaqtda telefoningizda «YukGo: buyurtma bajarilmoqda» bildirishnomasi turadi.</li>
          <li>Navigatsiyadan chiqsangiz yoki buyurtma yakunlansa, kuzatuv to'xtaydi. Boshqa paytlarda joylashuvingiz yig'ilmaydi.</li>
          <li>Joylashuvingizni shu buyurtma yuboruvchisi ko'radi (nizo bo'lsa — YukGo adminlari ham). <a href="/privacy.html" data-doc>Maxfiylik siyosati</a></li>
        </ul>
        <p class="small muted" style="margin:0">Keyingi qadamda telefon joylashuvga ruxsat so'raydi.</p>
        <div class="row" style="justify-content:flex-end">
          <button class="btn" data-close>Hozir emas</button>
          <button class="btn btn-primary" data-ok>Roziman</button>
        </div></div>`, {
      onMount: ({ el, close }) => {
        el.querySelector('[data-ok]').addEventListener('click', () => { done = true; close(); resolve(true); });
        const obs = new MutationObserver(() => {
          if (!document.body.contains(el)) { obs.disconnect(); if (!done) resolve(false); }
        });
        obs.observe(document.getElementById('modal-root'), { childList: true });
      },
    });
  });
}

export async function navigationPage(main, { id }) {
  let o = await get('/orders/' + id);
  const cap = nativeApp();
  if (o.viewerRole !== 'DRIVER' || !['ACCEPTED', 'IN_TRANSIT'].includes(o.status)) {
    toast("Navigatsiya faqat shu buyurtmaning haydovchisi uchun va buyurtma faol bo'lganda ishlaydi", 'err');
    go('#/orders/' + id);
    return;
  }

  main.innerHTML = `<div class="nav-screen">
    <div class="nav-map">
      <div class="map" id="nav-map"></div>
      <div class="nav-top">
        <button class="nav-fab" id="exit" aria-label="Orqaga">${icon('back')}</button>
        <div class="nav-instr" aria-live="polite"><b id="nav-dist">—</b><span id="nav-eta">Joylashuvingiz aniqlanmoqda…</span></div>
        <button class="nav-fab on" id="follow" aria-pressed="true" aria-label="Meni kuzatish">${icon('locate')}</button>
      </div>
    </div>
    <div class="nav-sheet" id="sheet"></div>
  </div>`;

  // Xarita yuklanmasa ham haydovchi amallarni bajara olishi va GPS uzatishi kerak
  let ctx = null;
  try {
    ctx = await createMap($('#nav-map', main), { zoom: 14, controls: ['zoomControl', 'trafficControl'] });
  } catch { /* xabar xarita joyida ko'rsatiladi */ }
  const ymaps = ctx?.ymaps, map = ctx?.map;
  map?.behaviors.enable('scrollZoom');

  let me = null, meMark = null, targetMark = null, routeObj = null;
  let lastRouteFrom = null, lastRouteAt = 0, follow = true, watchId = null, trackTimer = null, wakeLock = null;
  let lastFix = null;

  // Marshrut Yandex'dan faqat kerak bo'lganda qayta so'raladi (har bir so'rov kunlik limitdan ketadi):
  // haydovchi yo'ldan chiqib ketsa yoki tirbandlikka qarab vaqtni yangilash payti kelsa.
  // Qolgan masofa va vaqt esa har bir GPS yangilanishida qurilmaning o'zida hisoblanadi.
  let routeLine = null, routeDurationMin = null, routeFailed = false, routeSeq = 0;
  const OFF_ROUTE_M = 150;                // yo'ldan shuncha metr chiqsa — qayta hisoblash
  const MIN_REROUTE_MS = 30 * 1000;       // qayta hisoblashlar orasida kamida
  const FAILED_RETRY_MS = 2 * 60 * 1000;  // so'rov xato bo'lsa, qayta urinishgacha
  const REFRESH_MS = 15 * 60 * 1000;      // yetib borish vaqtini yangilash
  const fmtDist = (m) => (m < 1000 ? `${Math.max(10, Math.round(m / 10) * 10)} m` : km(m / 1000));
  const hhmm = (d) => `${String(d.getHours()).padStart(2, '0')}:${String(d.getMinutes()).padStart(2, '0')}`;

  async function target() {
    const pickup = o.status === 'ACCEPTED';
    const p = pickup ? o.from : o.to;
    let c = p.lat != null ? [p.lat, p.lng] : null;
    if (!c && ymaps) c = await geocode(ymaps, [p.address, p.districtName, p.regionName].filter(Boolean).join(', '));
    return { coords: c, pickup, place: p };
  }

  let tgt = await target();

  // Turga qarab matnlar: yo'lovchida «Yo'lovchini oldim» / «Yetib keldik» (kodsiz), yukda — kod bilan topshirish
  const kind = o.kind || 'CARGO';
  const passenger = kind === 'PASSENGER';
  const thing = passenger ? "Yo'lovchini" : kind === 'PARCEL' ? 'Pochtani' : 'Yukni';

  function renderSheet() {
    const pickup = tgt.pickup;
    // Yo'lovchi o'zi mashinada — yo'lda ham o'ziga qo'ng'iroq qilinadi
    const toSender = pickup || passenger || !o.receiverPhone;
    const contactName = toSender ? o.sender.fullName : (o.receiverName || 'Qabul qiluvchi');
    const contactPhone = toSender ? o.sender.phone : o.receiverPhone;
    const targetTitle = pickup ? (passenger ? "Yo'lovchini olish" : `${thing} olish`) : (passenger ? 'Manzil' : 'Topshirish');
    $('#sheet', main).innerHTML = `
      <div class="nav-target ${pickup ? '' : 'drop'}"><span class="dot" aria-hidden="true"></span>
        <div style="min-width:0"><b>${esc(targetTitle)}: ${esc(tgt.place.regionName)}${tgt.place.districtName ? ', ' + esc(tgt.place.districtName) : ''}</b>
        <div class="small muted">${esc(tgt.place.address || 'Aniq manzil kiritilmagan')}</div></div></div>
      <div class="nav-actions">
        <a class="btn btn-lg" href="tel:${esc(contactPhone)}" aria-label="${esc(contactName)} ga qo'ng'iroq">${icon('phone')}</a>
        <a class="btn btn-lg" href="#/orders/${o.id}" aria-label="Buyurtma tafsilotlari">${icon('list')}</a>
        ${pickup ? `<button class="btn btn-primary btn-lg" id="act">${esc(thing)} oldim</button>`
                 : `<button class="btn btn-ok btn-lg" id="act">${passenger ? 'Yetib keldik' : 'Topshirish'}</button>`}
      </div>
      <p class="tiny muted">${toSender ? (passenger ? "Yo'lovchi" : 'Yuboruvchi') : 'Qabul qiluvchi'}: ${esc(contactName)}, ${esc(phoneFmt(contactPhone))}</p>`;
    $('#act', main).addEventListener('click', pickup ? doPickup : doDeliver);
  }

  async function doPickup(e) {
    const r = await confirmDialog(passenger ? "Yo'lovchini oldingizmi?" : `${thing} oldingizmi?`,
      passenger ? "Endi borish manziliga yo'l ko'rsatamiz." : "Yuboruvchiga yo'lga chiqqaningiz haqida xabar boradi.", { okText: 'Ha, oldim' });
    if (!r) return;
    try {
      o = await busy($('#act', main), () => post(`/orders/${id}/pickup`));
      toast(passenger ? "Oq yo'l!" : "Endi topshirish manziliga yo'l ko'rsatamiz", 'ok');
      tgt = await target();
      placeTarget();
      renderSheet();
      routeLine = null;
      routeFailed = false;
      lastRouteFrom = null;
      lastRouteAt = 0;
      reroute(true);
    } catch (err) { toast(err.message, 'err'); }
    void e;
  }

  async function doDeliver() {
    if (passenger) {
      const r = await confirmDialog('Manzilga yetib keldingizmi?', "Safar yakunlanadi va yo'lovchiga baholash taklif qilinadi.", { okText: 'Ha, yetdik' });
      if (!r) return;
      try {
        await busy($('#act', main), () => post(`/orders/${id}/deliver`, {}));
        toast('Safar yakunlandi! Rahmat', 'ok');
        go('#/orders/' + id);
      } catch (err) { toast(err.message, 'err'); }
      return;
    }
    modal(kind === 'PARCEL' ? 'Pochtani topshirish' : 'Yukni topshirish', `<form class="stack" novalidate>
        <p class="small muted">Qabul qiluvchidan 4 xonali kodni so'rang va kiriting.</p>
        <input class="input code-input" name="code" inputmode="numeric" maxlength="4" autocomplete="off" required aria-label="Topshirish kodi">
        <button class="btn btn-ok btn-lg" type="submit">Tasdiqlash</button></form>`, {
      onMount: ({ el, close }) => {
        const f = $('form', el);
        f.addEventListener('submit', async (ev) => {
          ev.preventDefault();
          try {
            await busy(f.querySelector('[type=submit]'), () => post(`/orders/${id}/deliver`, { code: f.code.value.trim() }));
            close();
            toast('Topshirildi! Rahmat', 'ok');
            go('#/orders/' + id);
          } catch (err) { showError(f, err); }
        });
      },
    });
  }

  function placeTarget() {
    if (!map) return;
    if (targetMark) map.geoObjects.remove(targetMark);
    if (!tgt.coords) return;
    targetMark = pin(ymaps, tgt.coords, tgt.pickup ? 'from' : 'to', { label: tgt.pickup ? 'Olish' : 'Topshirish' });
    map.geoObjects.add(targetMark);
  }

  function showStraight() {
    $('#nav-dist', main).textContent = fmtDist(haversine(me, tgt.coords));
    $('#nav-eta', main).textContent = "To'g'ri chiziq bo'yicha masofa";
  }

  function showProgress(restM) {
    $('#nav-dist', main).textContent = fmtDist(restM);
    if (routeDurationMin != null && routeLine.totalM > 0) {
      const min = Math.max(1, Math.round(routeDurationMin * restM / routeLine.totalM));
      $('#nav-eta', main).textContent = `Yetib borish: ${hhmm(new Date(Date.now() + min * 60000))} (${min} daq)`;
    }
  }

  async function reroute(fit = false) {
    if (!me || !tgt.coords) return;
    lastRouteFrom = me;
    lastRouteAt = Date.now();
    if (!map) { routeLine = null; showStraight(); return; }
    const seq = ++routeSeq;
    const r = await drawRoute(ymaps, map, [me, tgt.coords], { fit, useCache: false });
    if (seq !== routeSeq) { map.geoObjects.remove(r.route); return; } // eskirgan javob (manzil o'zgargan)
    if (routeObj) map.geoObjects.remove(routeObj);
    routeObj = r.route;
    routeFailed = !!r.failed || r.distanceKm == null;
    routeLine = prepareRouteLine(r.coords);
    routeDurationMin = r.durationMin;
    if (routeLine) {
      showProgress(locateOnRoute(routeLine, me).restM);
    } else if (r.distanceKm != null) {
      $('#nav-dist', main).textContent = km(r.distanceKm);
      $('#nav-eta', main).textContent = r.durationMin != null
        ? `Yetib borish: ${hhmm(new Date(Date.now() + r.durationMin * 60000))} (${r.durationMin} daq)` : '';
    } else {
      showStraight();
    }
  }

  function onPosition(p) {
    me = [p.coords.latitude, p.coords.longitude];
    lastFix = p;
    if (map) {
      if (!meMark) {
        meMark = pin(ymaps, me, 'driver', { hint: 'Siz' });
        map.geoObjects.add(meMark);
        map.setCenter(me, 15);
      } else meMark.geometry.setCoordinates(me);
      if (follow) map.panTo(me, { flying: false, duration: 300 });
    }
    if (!tgt.coords) return;

    let needRoute;
    if (routeLine) {
      const pos = locateOnRoute(routeLine, me);
      needRoute = pos.offM > OFF_ROUTE_M;
      if (!needRoute) showProgress(pos.restM);
    } else if (routeFailed) {
      needRoute = true;
    } else {
      // Marshrut chizig'i yo'q (xarita yuklanmagan) — taxminiy masofa, 2 km yurganda yangilanadi
      needRoute = !lastRouteFrom || haversine(lastRouteFrom, me) > 2000;
      if (!map) showStraight();
    }
    const since = Date.now() - lastRouteAt;
    const gap = routeFailed ? FAILED_RETRY_MS : MIN_REROUTE_MS;
    if (!lastRouteAt || (needRoute && since > gap) || since > REFRESH_MS) reroute(!lastRouteAt);

    if (haversine(me, tgt.coords) < 150) {
      $('#nav-eta', main).textContent = tgt.pickup ? (passenger ? "Siz yo'lovchini olish joyidasiz" : 'Siz olish manzilidasiz')
        : (passenger ? 'Siz manzildasiz' : 'Siz topshirish manzilidasiz');
    }
  }

  function onError(err) {
    $('#nav-eta', main).textContent = err.code === 1
      ? "Joylashuvga ruxsat bering: brauzer sozlamalari → Joylashuv"
      : "GPS signal yo'q. Ochiq joyga chiqing";
  }

  let lastSentAt = 0;
  async function sendTrack() {
    if (!lastFix) return;
    const c = lastFix.coords;
    const body = {
      lat: c.latitude, lng: c.longitude,
      speedKmh: c.speed != null ? Math.round(c.speed * 3.6) : null,
      heading: c.heading ?? null,
    };
    lastSentAt = Date.now();
    try {
      if (cap && cap.Plugins.CapacitorHttp) {
        await cap.Plugins.CapacitorHttp.request({
          url: location.origin + `/api/orders/${id}/track`, method: 'POST',
          headers: { 'Content-Type': 'application/json', Authorization: 'Bearer ' + auth.token },
          data: body,
        });
      } else {
        await post(`/orders/${id}/track`, body);
      }
    } catch { /* tarmoq uzilsa keyingi safar yuboriladi */ }
  }

  $('#exit', main).addEventListener('click', () => go('#/orders/' + id));
  $('#follow', main).addEventListener('click', (e) => {
    follow = !follow;
    e.currentTarget.classList.toggle('on', follow);
    e.currentTarget.setAttribute('aria-pressed', String(follow));
    if (follow && me && map) map.setCenter(me, 16, { duration: 300 });
  });

  placeTarget();
  renderSheet();
  if (tgt.coords && map) map.setCenter(tgt.coords, 13);

  let bgWatcher = null;
  let disposed = false;          // sahifadan chiqildi — kechikib kelgan kuzatuvchi darhol to'xtatiladi
  let retryNative = false;       // ruxsat sozlamalarda berilsa, ilovaga qaytganda qayta ishga tushiramiz
  let permDialogShown = false;

  // Brauzer (va zaxira) usuli: faqat sahifa ochiq turganda ishlaydi
  function startWebTracking() {
    if (disposed || watchId != null) return;
    if (navigator.geolocation) {
      watchId = navigator.geolocation.watchPosition(onPosition, onError, { enableHighAccuracy: true, maximumAge: 3000, timeout: 20000 });
    } else {
      onError({ code: 2 });
    }
    if (!trackTimer) trackTimer = setInterval(sendTrack, 10000);
  }

  function stopWebTracking() {
    if (watchId != null) { navigator.geolocation.clearWatch(watchId); watchId = null; }
    if (trackTimer) { clearInterval(trackTimer); trackTimer = null; }
  }

  function askForPermission(BG, precise = false) {
    $('#nav-eta', main).textContent = precise
      ? "Ekran o'chganda ham kuzatish uchun «Aniq joylashuv» ruxsatini yoqing"
      : "Joylashuvga ruxsat bering: Sozlamalar → Ilovalar → YukGo → Joylashuv";
    retryNative = true;
    if (permDialogShown) return;
    permDialogShown = true;
    confirmDialog('Joylashuvga ruxsat kerak', precise
      ? "Hozir YukGo'da taxminiy joylashuv ruxsati bor — kuzatuv faqat ilova ochiq turganda ishlaydi. Ekran o'chiq bo'lsa ham "
        + "yuboruvchi sizni ko'rishi uchun Sozlamalar → Ruxsatlar → Joylashuv bo'limida «Aniq joylashuv»ni yoqing va shu sahifaga qayting."
      : "Yuboruvchi yukini kuzata olishi uchun YukGo'ga joylashuvdan foydalanishga ruxsat bering "
        + "(«Ilovadan foydalanayotganda» yoki «Har doim»). Keyin shu sahifaga qayting.",
      { okText: 'Sozlamalarni ochish' }).then((r) => { if (r) BG.openSettings(); });
  }

  /** Ogohlantirishga rozilik berilmadi: GPS yuborilmaydi, istalgan payt qayta yoqish mumkin */
  function disclosureDeclined() {
    const box = $('#nav-eta', main);
    box.innerHTML = `GPS yuborilmayapti — yuboruvchi yukni xaritada ko'rmaydi. <button class="link-btn nav-gps-on" type="button">Yoqish</button>`;
    $('.nav-gps-on', box).addEventListener('click', () => {
      box.textContent = 'Joylashuvingiz aniqlanmoqda…';
      startNativeTracking();
    });
  }

  // Android ilova: fondagi kuzatuv. Har bir yangi nuqtada (kamida 10 soniyada bir) yuboruvchiga uzatamiz —
  // JS taymerlari fonda sekinlashadi, joylashuv chaqiruvlari esa to'xtamaydi.
  async function startNativeTracking() {
    const BG = cap.Plugins.BackgroundGeolocation;
    retryNative = false;
    if (!disclosureAccepted()) {
      const ok = await locationDisclosure();
      if (disposed) return;
      if (!ok) { disclosureDeclined(); return; }
      rememberDisclosure();
    }
    const perm = await locationPermission();
    if (disposed) return;
    if (perm === 'denied') { askForPermission(BG); return; }
    try {
      const id = await Promise.resolve(BG.addWatcher({
        backgroundTitle: 'YukGo: buyurtma bajarilmoqda',
        backgroundMessage: "Joylashuvingiz yuboruvchiga ko'rsatilmoqda. Navigatsiyadan chiqsangiz to'xtaydi.",
        requestPermissions: false,   // ruxsat yuqorida so'raldi
        stale: false,
        distanceFilter: 10,
      }, (loc, err) => {
        if (disposed) return;
        if (err) {
          if (err.code === 'NOT_AUTHORIZED' && /disabled/i.test(err.message || '')) {
            // Ruxsat bor, lekin telefonda joylashuv (GPS) o'chiq — yoqilishi bilan nuqtalar o'zi kela boshlaydi
            $('#nav-eta', main).textContent = "Telefonda joylashuv (GPS) o'chiq — uni yoqing";
          } else if (err.code === 'NOT_AUTHORIZED') {
            // Tizim ruxsati bor (WebView joylashuvni oldi), lekin fon xizmatiga yetmaydi — odatda «Taxminiy joylashuv».
            // Fon kuzatuvchisini o'chirib, sahifa ochiq turganda brauzer usulida kuzatamiz
            const wid = bgWatcher;
            bgWatcher = null;
            if (wid != null) { try { BG.removeWatcher({ id: wid }); } catch { /* */ } }
            startWebTracking();
            askForPermission(BG, true);
          } else {
            startWebTracking();   // kutilmagan xato — hech bo'lmasa sahifa ochiq turganda kuzatamiz
          }
          return;
        }
        if (!loc) return;
        onPosition({ coords: { latitude: loc.latitude, longitude: loc.longitude, speed: loc.speed, heading: loc.bearing, accuracy: loc.accuracy } });
        if (Date.now() - lastSentAt >= 10000) sendTrack();
      }));
      if (disposed) { try { BG.removeWatcher({ id }); } catch { /* */ } return; }
      bgWatcher = id;
      stopWebTracking();   // sozlamalardan qaytib, fon kuzatuvi ishga tushdi — zaxira usul endi kerak emas
    } catch {
      startWebTracking();
    }
  }

  if (cap) {
    await startNativeTracking();
  } else {
    startWebTracking();
    myPosition().catch(() => {});
  }

  // Ekran o'chib qolmasin (haydovchi yo'lda)
  async function lockScreen() {
    try {
      wakeLock = await navigator.wakeLock?.request('screen');
      wakeLock?.addEventListener('release', () => { wakeLock = null; });
    } catch { /* qo'llab-quvvatlanmaydi */ }
  }
  await lockScreen();
  const onVis = () => {
    if (document.visibilityState !== 'visible') return;
    if (!wakeLock) lockScreen();
    if (cap && retryNative && bgWatcher == null && !disposed) startNativeTracking();   // sozlamalardan qaytdi
  };
  document.addEventListener('visibilitychange', onVis);

  return () => {
    disposed = true;
    if (watchId != null) navigator.geolocation.clearWatch(watchId);
    if (bgWatcher != null) { try { cap.Plugins.BackgroundGeolocation.removeWatcher({ id: bgWatcher }); } catch { /* */ } }
    clearInterval(trackTimer);
    document.removeEventListener('visibilitychange', onVis);
    try { wakeLock?.release(); } catch { /* */ }
    try { map?.destroy(); } catch { /* */ }
  };
}
