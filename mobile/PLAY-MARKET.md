# YukGo'ni Google Play'ga chiqarish

Qisqacha yo'l: **dasturchi hisobi → ilova yaratish → sahifa va so'rovnomalar → yopiq test (12 kishi, 14 kun) → production**.
Shaxsiy hisob uchun birinchi chiqarishgacha taxminan 3 hafta hisoblang.

> Bu yo'riqnoma 2026-yil oktabr holatiga tayanadi. Google talablari o'zgarib turadi — Play Console'dagi ko'rsatmalar ustun.
> Men yurist emasman: maxfiylik va ma'lumotlar bo'yicha savollarda mutaxassis bilan maslahatlashing.

## 0. Avval tayyorlab qo'ying

- [ ] Railway'da **`SUPPORT_EMAIL`** o'zgaruvchisi (masalan, o'zingizning Gmail'ingiz) — maxfiylik siyosati va
      "hisobni o'chirish" sahifalarida aloqa sifatida chiqadi. Play Console ham shu emailni so'raydi.
- [ ] Railway'da **`PUBLIC_URL=https://yukgoo-production.up.railway.app`** (Telegram tugmalari uchun ham kerak).
- [ ] **Tekshiruvchilar uchun sinov hisobi.** Google xodimlari ilovaga kirib ko'radi, ular Telegram kod ololmaydi.
      Saytda ikkinchi telefon raqamingiz bilan ro'yxatdan o'ting (masalan, "Test Haydovchi"), mashina qo'shing,
      ism-familiya va parolni yozib qo'ying. Kirish sahifasi ism + familiya + parol bilan ishlaydi.
- [ ] Imzolangan **`app-release.aab`** (README.md, 3-qadam).

## 1. Dasturchi hisobi

1. https://play.google.com/console → Google hisobingiz bilan kiring.
2. Hisob turi: **Shaxsiy** (oddiyroq, lekin yopiq test majburiy) yoki **Tashkilot** (MChJ, D-U-N-S raqami kerak, yopiq test talab qilinmaydi).
3. Bir martalik to'lov — **25 dollar** (Visa/Mastercard, oldindan to'langan karta emas), shaxsni tasdiqlash (pasport).
4. Android telefon orqali tasdiqlash (Play Console ilovasi).

## 2. Ilova yaratish

**Create app** → nomi: `YukGo — yuk tashish` · standart til: **o'zbek (uz)** · App · Free → shartlarga rozilik.

## 3. Asosiy sahifa (Store listing)

| Maydon | Qiymat |
|---|---|
| App name (30) | `YukGo — yuk tashish` |
| Short description (80) | `Yuk egalari va haydovchilar: e'lon, narx taklifi va yukni jonli kuzatish` |
| App icon 512×512 | `store/icon-512.png` |
| Feature graphic 1024×500 | `store/feature-graphic-1024x500.png` |
| Phone screenshots | `store/screenshot-1-bozor.png` … `screenshot-4-profil.png` (keyinroq haqiqiy telefondan olingan rasmlarga almashtirsangiz yaxshi) |
| Category | **Business** (yoki *Auto & Vehicles*) |
| Email | `SUPPORT_EMAIL` dagi email |
| Website | `https://yukgoo-production.up.railway.app` |
| Privacy policy | `https://yukgoo-production.up.railway.app/privacy.html` |

**Full description (to'liq tavsif):**

```
YukGo — O'zbekiston bo'ylab yuk tashishni oson qiladigan xizmat: viloyatdan viloyatga va shahar ichida.

📦 Yuk egalari uchun
• Yukingizni bir daqiqada e'lon qiling: qayerdan, qayerga, yuk turi, og'irligi, sana va narx
• Haydovchilar narx taklif qiladi — reyting, mashina va baholarga qarab tanlaysiz
• Yukingiz qayerdaligini xaritada jonli kuzatasiz
• Yuk faqat qabul qiluvchi aytgan 4 xonali kod bilan topshiriladi
• Shoshilinch e'lonlar ro'yxatda doim eng yuqorida turadi

🚚 Haydovchilar uchun
• Eng yaqin sanadagi buyurtmalar birinchi chiqadi: bugun, ertaga, indinga…
• Yo'nalish, yuk turi va mashina turi bo'yicha filtrlar
• Narxga rozi bo'lib darhol oling yoki o'z narxingizni taklif qiling
• Ilova ichida Yandex xarita bilan navigatsiya, ekran o'chiq bo'lsa ham yuboruvchi sizni ko'rib turadi
• Yangi buyurtmalar haqida Telegram orqali darhol xabar

🔒 Ishonch va xavfsizlik
• Telefon raqamingiz faqat buyurtma tomonlariga ko'rinadi
• Ro'yxatdan o'tish Telegram orqali tasdiqlanadi
• Reyting, sharhlar va shikoyat qilish imkoniyati

YukGo Telegram'da ham bor: @YukGoobot
```

## 4. "App content" bo'limi (Dashboard'dagi vazifalar)

**Privacy policy:** `https://yukgoo-production.up.railway.app/privacy.html`

**App access → All or some functionality is restricted → Add instructions:**
```
Login: open the app → "Kirish" → enter First name, Last name and Password:
First name: <test ism>   Last name: <test familiya>   Password: <parol>
The account has a vehicle, so driver features (offers, navigation) are available.
```

**Ads:** No.

**Content rating** (IARC so'rovnomasi): kategoriya — *All other app types*. Foydalanuvchilar o'zaro yozisha oladi va
sharh qoldiradi — **Ha**; joylashuv boshqa foydalanuvchiga ko'rsatiladi — **Ha**; raqamli xaridlar — **Yo'q**
(daromad tizimi yoqilgach balans to'ldirish ilova tashqarisida bo'lsa ham shu yerni qayta ko'rib chiqing).

**Target audience:** faqat **18+**.

**News app:** No · **Government app:** No · **Health:** No.

**Data safety** (taxminiy javoblar — o'zingiz tekshirib chiqing):

| Ma'lumot turi | Yig'iladi | Boshqalarga ko'rinadi | Maqsad |
|---|---|---|---|
| Name | Ha | Ha (profil, buyurtma tomoni) | App functionality, Account management |
| Phone number | Ha | Ha (faqat buyurtma tomoniga) | App functionality, Account management |
| Address | Ha | Ha (buyurtma manzillari haydovchiga) | App functionality |
| User IDs (Telegram ID) | Ha | Yo'q | App functionality, Account management |
| Precise location | Ha (faqat haydovchi navigatsiyada) | Ha (buyurtma yuboruvchisiga) | App functionality |
| Photos | Ha (profil, mashina, yuk rasmi) | Ha | App functionality |
| Messages (in-app) | Ha | Ha (buyurtma tomoniga) | App functionality |
| Other user-generated content (sharhlar, e'lonlar) | Ha | Ha | App functionality |

- Data encrypted in transit: **Yes** (HTTPS)
- Users can request deletion: **Yes** → `https://yukgoo-production.up.railway.app/delete-account.html`
- Ma'lumotlar sotilmaydi va reklama uchun ishlatilmaydi.

**Foreground service permissions** (Android 14+ talabi) → **Location**:
```
Drivers open the in-app navigation for an order they are delivering. While it is open, the app shows an
ongoing notification and sends the driver's location to the sender of that order, so the sender can track
the cargo in real time. Tracking starts only when the driver opens navigation and stops when they leave it.
```
Video havolasi so'raladi: telefonda navigatsiyani ochib, bildirishnoma chiqqanini ko'rsatadigan 20–30 soniyalik
ekran yozuvini YouTube'ga **Unlisted** qilib yuklang va havolasini qo'ying.

**Account deletion:** ilova ichida — Profil → "Hisobni o'chirish"; web havola — `…/delete-account.html`.

## 5. Yopiq test (shaxsiy hisob uchun majburiy)

1. **Testing → Closed testing → Create track** (masalan, "Haydovchilar").
2. **Testers:** kamida **12 ta** Google hisobi (Gmail) ro'yxati — tanish haydovchilar, do'stlar, oila.
3. **Create release** → `app-release.aab` ni yuklang → reliz izohi (masalan, "Birinchi sinov versiyasi") → **Review and roll out**.
4. Testerlarga **"Join on the web"** havolasini yuboring: ular rozilik bildirib, Play'dan ilovani o'rnatadi.
5. Kamida 12 kishi **14 kun uzluksiz** testda qolishi kerak (kim chiqib ketsa — hisob noldan boshlanishi mumkin).
   Bu vaqtda ulardan fikr so'rang va topilgan xatolarni tuzating — keyingi savollarda shu so'raladi.
6. 14 kundan keyin: **Dashboard → Apply for production** → so'rovnomani batafsil to'ldiring
   (kim sinadi, qanday fikrlar bo'ldi, nimani o'zgartirdingiz).

## 6. Production

Ruxsat kelgach: **Production → Create release** → AAB → **Countries: Uzbekistan** (va kerakli boshqalar) →
**Review and roll out**. Birinchi tekshiruv bir necha kundan bir haftagacha davom etishi mumkin.

## Yangilanishlar

- Faqat sayt o'zgarsa — Railway'ga push kifoya, ilova o'zi yangilanadi.
- Ilovaning o'zi o'zgarsa — `versionCode` ni oshirib, yangi AAB ni yuklang (README.md, 4-qadam).
