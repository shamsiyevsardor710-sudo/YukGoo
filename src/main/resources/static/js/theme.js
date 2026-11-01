// Kunduzgi va kechki rejim. Birinchi kirishda — kechki (qorong'i); tanlov shu qurilmada eslab qolinadi.
// index.html sahifa chizilishidan oldin shu kalitni o'qib, data-theme ni qo'yadi (oq «miltillash» bo'lmasin).
const KEY = 'yukgo_theme';
const listeners = new Set();
/** Brauzer xotirasi yopiq bo'lsa (maxfiy oyna) — tanlov shu sahifa ochiq turganda eslab qolinadi */
let memory = null;

function stored() {
  try { return localStorage.getItem(KEY) ?? memory; } catch { return memory; }
}

/** 'dark' (standart) yoki 'light' */
export function currentTheme() {
  return stored() === 'light' ? 'light' : 'dark';
}

export function themeLabel(t = currentTheme()) {
  return t === 'dark' ? 'Kechki' : 'Kunduzgi';
}

export function applyTheme() {
  const t = currentTheme();
  document.documentElement.dataset.theme = t;
  const meta = document.querySelector('meta[name="theme-color"]');
  if (meta) meta.setAttribute('content', t === 'dark' ? '#0C1113' : '#1F2A30');
  for (const fn of listeners) { try { fn(t); } catch { /* */ } }
  return t;
}

export function setTheme(t) {
  memory = t === 'light' ? 'light' : 'dark';
  try { localStorage.setItem(KEY, memory); } catch { /* xotira yopiq */ }
  return applyTheme();
}

export function toggleTheme() {
  return setTheme(currentTheme() === 'dark' ? 'light' : 'dark');
}

/** Rejim o'zgarganda (masalan, Telegram sarlavha rangini moslash uchun) */
export function onThemeChange(fn) {
  listeners.add(fn);
  return () => listeners.delete(fn);
}

applyTheme();
