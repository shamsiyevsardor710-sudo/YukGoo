// Haydovchi navigatsiyasi: ilova ichida Yandex xarita, jonli joylashuv, marshrut va yuboruvchiga GPS uzatish
import { get, post } from '../api.js';
import { $, esc, icon, toast, km, phoneFmt, modal, showError, busy, confirmDialog } from '../ui.js';
import { createMap, pin, drawRoute, geocode, haversine, myPosition } from '../maps.js';
import { go } from '../app.js';

export async function navigationPage(main, { id }) {
  let o = await get('/orders/' + id);
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

  async function target() {
    const pickup = o.status === 'ACCEPTED';
    const p = pickup ? o.from : o.to;
    let c = p.lat != null ? [p.lat, p.lng] : null;
    if (!c && ymaps) c = await geocode(ymaps, [p.address, p.districtName, p.regionName].filter(Boolean).join(', '));
    return { coords: c, pickup, place: p };
  }

  let tgt = await target();

  function renderSheet() {
    const pickup = tgt.pickup;
    const contactName = pickup ? o.sender.fullName : o.receiverName;
    const contactPhone = pickup ? o.sender.phone : o.receiverPhone;
    $('#sheet', main).innerHTML = `
      <div class="nav-target ${pickup ? '' : 'drop'}"><span class="dot" aria-hidden="true"></span>
        <div style="min-width:0"><b>${pickup ? 'Yukni olish' : 'Topshirish'}: ${esc(tgt.place.regionName)}${tgt.place.districtName ? ', ' + esc(tgt.place.districtName) : ''}</b>
        <div class="small muted">${esc(tgt.place.address || 'Aniq manzil kiritilmagan')}</div></div></div>
      <div class="nav-actions">
        <a class="btn btn-lg" href="tel:${esc(contactPhone)}" aria-label="${esc(contactName)} ga qo'ng'iroq">${icon('phone')}</a>
        <a class="btn btn-lg" href="#/orders/${o.id}" aria-label="Buyurtma tafsilotlari">${icon('list')}</a>
        ${pickup ? `<button class="btn btn-primary btn-lg" id="act">Yukni oldim</button>`
                 : `<button class="btn btn-ok btn-lg" id="act">Topshirish</button>`}
      </div>
      <p class="tiny muted">${pickup ? 'Yuboruvchi' : 'Qabul qiluvchi'}: ${esc(contactName)}, ${esc(phoneFmt(contactPhone))}</p>`;
    $('#act', main).addEventListener('click', pickup ? doPickup : doDeliver);
  }

  async function doPickup(e) {
    const r = await confirmDialog('Yukni oldingizmi?', "Yuboruvchiga yuk yo'lga chiqqani haqida xabar boradi.", { okText: 'Ha, oldim' });
    if (!r) return;
    try {
      o = await busy($('#act', main), () => post(`/orders/${id}/pickup`));
      toast("Endi topshirish manziliga yo'l ko'rsatamiz", 'ok');
      tgt = await target();
      placeTarget();
      renderSheet();
      lastRouteAt = 0;
      reroute(true);
    } catch (err) { toast(err.message, 'err'); }
    void e;
  }

  function doDeliver() {
    modal('Yukni topshirish', `<form class="stack" novalidate>
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
            toast('Yuk topshirildi! Rahmat', 'ok');
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

  async function reroute(fit = false) {
    if (!me || !tgt.coords) return;
    lastRouteFrom = me;
    lastRouteAt = Date.now();
    if (!map) {
      $('#nav-dist', main).textContent = km(haversine(me, tgt.coords) / 1000);
      $('#nav-eta', main).textContent = "To'g'ri chiziq bo'yicha masofa";
      return;
    }
    const old = routeObj;
    const r = await drawRoute(ymaps, map, [me, tgt.coords], { fit });
    if (old) map.geoObjects.remove(old);
    routeObj = r.route;
    if (r.distanceKm != null) {
      $('#nav-dist', main).textContent = km(r.distanceKm);
      const eta = new Date(Date.now() + r.durationMin * 60000);
      $('#nav-eta', main).textContent = `Yetib borish: ${String(eta.getHours()).padStart(2, '0')}:${String(eta.getMinutes()).padStart(2, '0')} (${r.durationMin} daq)`;
    } else {
      const straight = haversine(me, tgt.coords) / 1000;
      $('#nav-dist', main).textContent = km(straight);
      $('#nav-eta', main).textContent = "To'g'ri chiziq bo'yicha masofa";
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
    const moved = lastRouteFrom ? haversine(lastRouteFrom, me) : Infinity;
    if (moved > 250 || Date.now() - lastRouteAt > 60000) reroute(!lastRouteFrom);
    if (tgt.coords && haversine(me, tgt.coords) < 150) {
      $('#nav-eta', main).textContent = tgt.pickup ? 'Siz yuk olish manzilidasiz' : 'Siz topshirish manzilidasiz';
    }
  }

  function onError(err) {
    $('#nav-eta', main).textContent = err.code === 1
      ? "Joylashuvga ruxsat bering: brauzer sozlamalari → Joylashuv"
      : "GPS signal yo'q. Ochiq joyga chiqing";
  }

  async function sendTrack() {
    if (!lastFix) return;
    const c = lastFix.coords;
    try {
      await post(`/orders/${id}/track`, {
        lat: c.latitude, lng: c.longitude,
        speedKmh: c.speed != null ? Math.round(c.speed * 3.6) : null,
        heading: c.heading ?? null,
      });
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

  if (navigator.geolocation) {
    watchId = navigator.geolocation.watchPosition(onPosition, onError, { enableHighAccuracy: true, maximumAge: 3000, timeout: 20000 });
  } else {
    onError({ code: 2 });
  }
  myPosition().catch(() => {});
  trackTimer = setInterval(sendTrack, 10000);

  // Ekran o'chib qolmasin (haydovchi yo'lda)
  async function lockScreen() {
    try {
      wakeLock = await navigator.wakeLock?.request('screen');
      wakeLock?.addEventListener('release', () => { wakeLock = null; });
    } catch { /* qo'llab-quvvatlanmaydi */ }
  }
  await lockScreen();
  const onVis = () => { if (document.visibilityState === 'visible' && !wakeLock) lockScreen(); };
  document.addEventListener('visibilitychange', onVis);

  return () => {
    if (watchId != null) navigator.geolocation.clearWatch(watchId);
    clearInterval(trackTimer);
    document.removeEventListener('visibilitychange', onVis);
    try { wakeLock?.release(); } catch { /* */ }
    try { map?.destroy(); } catch { /* */ }
  };
}
