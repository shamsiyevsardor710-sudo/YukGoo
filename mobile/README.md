# YukGo Android ilovasi

Ilova jonli saytni ochadi (`capacitor.config.json` → `server.url`). Saytni yangilasangiz (Railway'ga push),
ilovadagi foydalanuvchilar ham yangi versiyani darhol ko'radi — Play Market'ga qayta yuklash faqat ilovaning
o'zi (ikonka, ruxsatlar, pluginlar, sayt manzili) o'zgarganda kerak.

Saytdan farqi:
- **Fondagi GPS** — haydovchi navigatsiyani ochganda ekran o'chiq yoki boshqa ilova ochiq bo'lsa ham kuzatuv davom etadi.
  Bu vaqtda telefonda "YukGo: buyurtma bajarilmoqda" bildirishnomasi turadi (Android talabi), navigatsiyadan chiqqanda to'xtaydi.
- Ilova ikonkasi, to'q rangli ishga tushish ekrani, internet yo'q bo'lsa — "Qayta urinish" sahifasi.
- Telefonning **«Orqaga»** tugmasi: ochiq oynani yopadi, keyin ilova ichida orqaga qaytadi, birinchi sahifada ilovani yig'ib qo'yadi.

## Kerakli dasturlar (bir marta)

1. **Node.js 20 yoki yangisi** — https://nodejs.org (tekshirish: `node -v`)
2. **Android Studio** (eng so'nggi versiya) — https://developer.android.com/studio
   O'rnatishda "Android SDK" belgilangan bo'lsin. Java alohida kerak emas — Android Studio o'zinikini ishlatadi.

## 1. Loyihani ochish

```powershell
cd C:\Users\shams\Downloads\yukgo\yukgo\mobile
npm install
npx cap sync android
npx cap open android
```

Android Studio ochiladi. Birinchi marta Gradle kutubxonalarni yuklaydi (5–15 daqiqa, internet kerak) —
pastdagi holat satrida "Gradle sync finished" chiqguncha kuting.

> `npm install` oxirida "3 moderate severity vulnerabilities" yozuvi chiqishi mumkin — bu faqat kompyuterdagi Capacitor
> buyruq qatori vositasining iOS qismiga tegishli, telefondagi ilovaga kirmaydi. **`npm audit fix --force` ni ishlatmang** —
> u vositaning versiyasini almashtirib, loyihani buzadi.

> `npx cap open android` Android Studio'ni topa olmasa: Android Studio'ni o'zingiz oching → **Open** → `mobile\android` papkasini tanlang.

## 2. Telefonda sinash

1. Telefonda: Sozlamalar → Telefon haqida → **"Build number" (Сборка)** ni 7 marta bosing → "Dasturchi rejimi" yoqiladi.
2. Sozlamalar → Dasturchilar uchun → **USB debugging** ni yoqing.
3. Telefonni USB orqali kompyuterga ulang, telefonda "Ruxsat berish" ni bosing.
4. Android Studio tepasida telefoningizni tanlang va yashil **▶ Run** tugmasini bosing.

Nimalarni tekshirish kerak:
- Kirish, bozor (sanalar bo'yicha guruhlar), buyurtma berish, rasm yuklash.
- Haydovchi hisobida buyurtmani oling → **Navigatsiya** → joylashuv va bildirishnomaga ruxsat bering →
  ekranni o'chiring → boshqa telefonda (yuboruvchi hisobida) mashina xaritada yurayotganini ko'ring.
- Qo'ng'iroq tugmasi telefon ilovasini ochadi, Telegram havolalari Telegram'da ochiladi.

## 3. Play Market uchun fayl (AAB)

1. Android Studio: **Build → Generate Signed App Bundle or APK…** → **Android App Bundle** → Next.
2. **Key store path → Create new…**
   - Fayl: masalan `C:\Users\shams\yukgo-upload.jks` (loyiha papkasidan TASHQARIDA)
   - Parol, Alias: `yukgo`, Validity: 25 yil, ism-familiya
3. Next → **release** → Create.
4. Tayyor fayl: `mobile\android\app\release\app-release.aab` — shuni Play Console'ga yuklaysiz.

> ⚠️ **Keystore (.jks) fayli va parollarini** xavfsiz joyda saqlang (masalan, Google Drive'da shifrlangan papka).
> Uni **GitHub'ga yuklamang** (`.gitignore` ham bunga yo'l qo'ymaydi). Har bir yangilanish shu kalit bilan imzolanadi.

## 4. Keyingi versiyalar

1. `android/app/build.gradle` da `versionCode` ni bittaga oshiring (1 → 2 → 3 …), `versionName` ni o'zgartiring (`1.0.1`).
2. `npx cap sync android`
3. Android Studio → Generate Signed App Bundle (o'sha keystore bilan) → Play Console'ga yangi reliz.

Faqat sayt (HTML/JS/Java backend) o'zgargan bo'lsa — bu qadamlar kerak emas, Railway'ga push yetarli.

## Muhim eslatmalar

- **Sayt manzili** `capacitor.config.json` dagi `server.url` (hozir `https://yukgoo-production.up.railway.app`) va
  `www/index.html`, `www/offline.html` ichida. O'z domeningizga o'tsangiz — uchala joyni o'zgartirib, ilovani qayta yuklang.
- **`useLegacyBridge: true`** (`capacitor.config.json`) ataylab qo'yilgan: fondagi GPS plagini shusiz 5 daqiqadan keyin
  joylashuv yuborishni to'xtatadi (plagin hujjatidagi talab). Ilova faqat o'z saytingizni ochadi — sahifaga begona
  saytlarning iframe'larini joylashtirmang.
- **Batareya tejash.** Ba'zi telefonlar (Xiaomi, Redmi, Samsung, Huawei) fondagi ilovalarni majburan to'xtatadi.
  Haydovchilarga ayting: Sozlamalar → Ilovalar → YukGo → Batareya → **"Cheklovsiz"**.
- Ikonka va ishga tushish ekrani `android/app/src/main/res` ichida tayyor; manba rasmlar — `assets/`.
- Play Market sahifasi uchun rasm va matnlar — `store/` papkasi va `PLAY-MARKET.md`.
