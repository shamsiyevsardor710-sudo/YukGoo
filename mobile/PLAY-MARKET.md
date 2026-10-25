# YukGo'ni Google Play'ga chiqarish

Qisqacha yo'l: **Railway Hobby → dasturchi hisobi → ilova yaratish → sahifa va so'rovnomalar → yopiq test (12 kishi, 14 kun) → production**.
Shaxsiy hisob uchun birinchi chiqarishgacha taxminan 3–4 hafta hisoblang.

> Bu yo'riqnoma 2026-yil oktabr oxiridagi holatga tayanadi. Google talablari o'zgarib turadi — Play Console'dagi ko'rsatmalar ustun.
> Men yurist emasman: maxfiylik va ma'lumotlar bo'yicha savollarda mutaxassis bilan maslahatlashing.

Ilova Google'ning hozirgi texnik talablariga mos: Android 16 (API 36) ga mo'ljallangan (2026-yil 31-avgustdan yangi ilovalar
uchun majburiy), AAB formatida yig'iladi, fondagi joylashuv ruxsati (background location) so'ralmaydi.

## 0. Avval tayyorlab qo'ying

- [ ] **Railway: Hobby tarifi** (yoki to'liq «Trial»). Ilova jonli saytni ochadi, shuning uchun sayt va bot butun sinov davomida
      ishlab turishi kerak. «Limited Trial»da tashqi tarmoq cheklangan: bot Telegram'ga ulana olmaydi va SMS ketmaydi, shuning
      uchun testerlar ham, Google tekshiruvchilari ham ro'yxatdan o'ta olmaydi. Sinov muddati taxminan 14-noyabrda tugaydi va 14 kunlik yopiq test o'rtasiga to'g'ri keladi.
      Testni boshlashdan oldin Hobby'ga o'ting.
- [ ] Railway'da **`SUPPORT_EMAIL`** o'zgaruvchisi (masalan, o'zingizning Gmail'ingiz). U maxfiylik siyosati va
      "hisobni o'chirish" sahifalarida aloqa sifatida chiqadi. Play Console ham shu emailni so'raydi.
- [ ] Railway'da **`PUBLIC_URL=https://yukgoo-production.up.railway.app`** (Telegram tugmalari uchun ham kerak).
- [ ] **Tekshiruvchilar uchun sinov hisobi.** Google xodimlari ilovaga kirib ko'radi, ular O'zbekiston raqamiga keladigan
      SMS kodni ololmaydi.
      Saytda ikkinchi telefon raqamingiz bilan ro'yxatdan o'ting (masalan, "Test Haydovchi"), mashina qo'shing,
      ism-familiya, telefon va parolni yozib qo'ying. Kirish sahifasi ism + familiya + parol bilan ishlaydi,
      «Telefon raqam bilan kirish» tugmasi orqali esa telefon + parol bilan.
- [ ] Imzolangan **`app-release.aab`** (README.md, 3-qadam).
- [ ] **15–20 ta testerning Gmail manzili** (5-bo'lim).
- [ ] **Android telefon**: dasturchi hisobini tasdiqlash va 4-bo'limdagi 20–30 soniyalik ekran yozuvi uchun.

## 1. Dasturchi hisobi

1. https://play.google.com/console → Google hisobingiz bilan kiring.
2. Hisob turi: **Shaxsiy** (oddiyroq, lekin yopiq test majburiy) yoki **Tashkilot** (MChJ, D-U-N-S raqami kerak, yopiq test talab qilinmaydi).
3. Bir martalik to'lov **25 dollar**. Visa yoki Mastercard bo'lishi kerak, oldindan to'langan (prepaid) karta qabul qilinmaydi.
   Karta va pasport bir xil ism-familiyada bo'lsin, aks holda tasdiqlash o'tmaydi va to'lov qaytarilmaydi.
   Shaxsni tasdiqlash bir necha kun olishi mumkin.
4. Android telefon orqali tasdiqlash (Play Console ilovasi).

## 2. Ilova yaratish

**Create app** → App name: `YukGo — yuk tashish` · App · Free → shartlarga rozilik.

**Default language: English (United States) – en-US.** Play Console tillari ro'yxatida o'zbek tili yo'q (qozoq va qirg'iz bor),
shuning uchun quyidagi o'zbekcha matnni shu tilga yozasiz. Rus tilidagi telefonlarda 3-bo'limdagi ruscha tarjima ko'rinadi,
qolgan hamma telefonda o'zbekcha matn.

## 3. Asosiy sahifa (Store listing)

| Maydon | Qiymat |
|---|---|
| App name (30) | `YukGo — yuk tashish` |
| Short description (80) | `Yuk egalari va haydovchilar: e'lon, narx taklifi va yukni jonli kuzatish` |
| App icon 512×512 | `store/icon-512.png` |
| Feature graphic 1024×500 | `store/feature-graphic-1024x500.png` |
| Phone screenshots | `store/screenshot-1-bozor.png` … `screenshot-4-profil.png` (oddiy foydalanuvchi ko'rinishi; keyinroq haqiqiy telefondan olingan rasmlarga almashtirsangiz ham bo'ladi) |
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
• Ro'yxatdan o'tish telefon raqamga SMS kod bilan tasdiqlanadi
• Tekshirilgan haydovchilar: hujjatlarini YukGo jamoasi tekshirgan
• Reyting, sharhlar va shikoyat qilish imkoniyati

YukGo Telegram'da ham bor: @YukGoobot — botga oddiy yozib ham e'lon bera olasiz.
```

### Ruscha tarjima (ixtiyoriy, lekin tavsiya qilinadi)

**Store listing → Manage translations → Add your own translations → Russian – ru-RU:**

| Maydon | Qiymat |
|---|---|
| App name | `YukGo — грузоперевозки` |
| Short description | `Отправители и водители: заявки на груз, торг по цене и отслеживание онлайн` |

Rasmlar uchun alohida narsa kerak emas, asosiy tildagilar ko'rsatiladi.

```
YukGo — сервис грузоперевозок по Узбекистану: между областями и по городу.
Интерфейс приложения на узбекском языке.

📦 Для отправителей
• Разместите заявку за минуту: откуда, куда, тип груза, вес, дата и цена
• Водители предлагают цену — выбирайте по рейтингу, машине и отзывам
• Следите за грузом на карте в реальном времени
• Груз передаётся только по 4-значному коду, который назовёт получатель
• Срочные заявки всегда вверху списка

🚚 Для водителей
• Сначала заказы на ближайшие даты: сегодня, завтра, послезавтра…
• Фильтры по направлению, типу груза и кузова
• Берите заказ по указанной цене или предложите свою
• Навигация с Яндекс Картами внутри приложения: отправитель видит вас, даже когда экран выключен
• Мгновенные уведомления о новых заказах в Telegram

🔒 Доверие и безопасность
• Ваш номер телефона видят только стороны заказа
• Регистрация подтверждается SMS-кодом на номер телефона
• Проверенные водители: их документы проверила команда YukGo
• Рейтинг, отзывы и возможность пожаловаться

YukGo есть и в Telegram: @YukGoobot — заявку можно разместить, просто написав боту.
```

## 4. "App content" bo'limi (Dashboard'dagi vazifalar)

**Privacy policy:** `https://yukgoo-production.up.railway.app/privacy.html`

**App access → All or some functionality is restricted → Add instructions:**
```
Login is required. Open the app → "Kirish" (Log in) and use either option:
• First name: <test ism>   Last name: <test familiya>   Password: <parol>
• or tap "Telefon raqam bilan kirish", then Phone: <+998 ...>   Password: <parol>
The test account has a vehicle, so driver features (offers, navigation) are available.
New sign-ups need an SMS code sent to an Uzbek phone number, so please use this account.
```

**Ads:** No.

**Content rating** (IARC so'rovnomasi): kategoriya — *All other app types*. Foydalanuvchilar o'zaro yozisha oladi va
sharh qoldiradi — **Ha**; joylashuv boshqa foydalanuvchiga ko'rsatiladi — **Ha**; raqamli xaridlar — **Yo'q**
(daromad tizimini yoqqaningizda qayta ko'ring, 7-bo'lim).

**Target audience:** faqat **18+**.

**News app:** No · **Government app:** No · **Health:** No.

**Financial features:** *My app doesn't provide any financial features* (daromad tizimi o'chiq ekan; yoqqaningizda qayta ko'ring).

**Advertising ID:** No (ilova reklama identifikatoridan foydalanmaydi).

**Data safety** (taxminiy javoblar — o'zingiz tekshirib chiqing):

- Does your app collect or share any of the required user data types? **Yes**
- Is all of the user data collected by your app encrypted in transit? **Yes** (HTTPS)
- Account creation: **Username, password and other authentication** (telefon + parol, SMS yoki Telegram orqali tasdiqlash)
- Delete account URL: `https://yukgoo-production.up.railway.app/delete-account.html`
- Hisobni o'chirmasdan ma'lumotni o'chirish so'rovi: **No**

| Ma'lumot turi (Play'dagi nomi) | Collected | Shared | Majburiymi | Maqsad |
|---|---|---|---|---|
| Personal info → Name | Ha | Yo'q | Required | App functionality, Account management |
| Personal info → Phone number | Ha | Yo'q | Required | App functionality, Account management |
| Personal info → User IDs (Telegram ID) | Ha | Yo'q | Required | App functionality, Account management |
| Personal info → Address (buyurtma manzillari) | Ha | Yo'q | Optional | App functionality |
| Location → Precise location (faqat haydovchi navigatsiyasida) | Ha | Yo'q | Optional | App functionality |
| Photos and videos → Photos (profil, mashina, yuk, haydovchi hujjatlari) | Ha | Yo'q | Optional | App functionality, Account management |
| Messages → Other in-app messages (buyurtma yozishmalari) | Ha | Yo'q | Optional | App functionality |
| App activity → Other user-generated content (e'lonlar, sharhlar) | Ha | Yo'q | Optional | App functionality |
| Financial info → Purchase history (balansni to'ldirish: summa, vaqt, to'lov raqami) | Ha | Yo'q | Optional | App functionality |

Karta ma'lumotlari («Financial info → User payment info») **yig'ilmaydi**: karta Click, Payme yoki Paynet'ning o'z sahifasida
yoki ilovasida kiritiladi va YukGo'ga kelmaydi.

**«Shared» nega «Yo'q».** Google «sharing» deb ma'lumotni **uchinchi tomonga** (boshqa kompaniyaga) berishni aytadi.
Bunga ikki holat kirmaydi:
- Foydalanuvchining o'z harakati bilan ko'rinadigan ma'lumot. Masalan, e'lon bergan yoki buyurtmani olgan odamning ismi,
  telefoni va joylashuvi buyurtma tomoniga ko'rinadi.
- Xizmat ko'rsatuvchilar: Telegram (xabarlar), Eskiz.uz (SMS), Click, Payme va Paynet (to'lovlar), Yandex (xarita), Railway (server).

Ikkilansangiz «Ha» deb belgilashingiz ham mumkin. Unda sahifada «ma'lumot uchinchi tomonga beriladi» deb ko'rinadi.

Haydovchi hujjatlari (guvohnoma va texpasport rasmi) faqat tekshiruv uchun olinadi. Ularni faqat admin ko'radi, qarordan
keyin o'chiriladi. Ma'lumotlar sotilmaydi va reklama uchun ishlatilmaydi.

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
2. **Countries / regions:** Uzbekistan.
3. **Testers:** kamida **12 ta** Google hisobi (Gmail). **15–20 ta** qo'shing, chunki kimdir chiqib ketishi mumkin.
   Tanish haydovchilar, yuk egalari, do'stlar va oila a'zolarini qo'shing.
4. **Create release** → `app-release.aab` ni yuklang → reliz izohi (masalan, "Birinchi sinov versiyasi") → **Review and roll out**.
   Birinchi tekshiruv bir necha soatdan bir necha kungacha davom etadi.
5. Testerlarga **"Join on the web"** havolasini va quyidagi xabarni yuboring.
6. 14 kun davomida kamida 12 kishi testda uzluksiz turishi kerak. Kim chiqib qaytsa, uning 14 kuni boshidan sanaladi.
   Google testerlar ilovani haqiqatan ishlatganini ham tekshiradi. Shuning uchun ulardan bir necha marta kirib, e'lon berib
   yoki taklif yuborib ko'rishni so'rang. Fikrlarini yozib boring va topilgan xatolarni tuzating, keyingi savollarda shu so'raladi.
7. 14 kundan keyin: **Dashboard → Apply for production**. So'rovnoma uch qismdan iborat:
   - yopiq test qanday o'tgani va testerlar fikri;
   - ilova kimlar uchun va nima foyda beradi;
   - test natijasida nimani o'zgartirdingiz.
   Batafsil yozing. Javob odatda 7 kun ichida emailga keladi.

**Testerlarga xabar (nusxalab yuboring):**
```
Assalomu alaykum! YukGo yuk tashish ilovasini Google Play'ga chiqaryapmiz, 14 kunlik sinovga yordamingiz kerak.

1) Telefoningizdagi Gmail hisobi bilan shu havolani oching va «Become a tester» ni bosing:
   <Join on the web havolasi>
2) Keyin «Download it on Google Play» orqali ilovani o'rnating.
3) 14 kun davomida ilovani o'chirmang va testdan chiqmang. Haftada 2–3 marta ochib ko'ring:
   bozorni ko'ring, yuk e'lon qiling yoki taklif yuboring.

Xato yoki taklif bo'lsa, shu yerga yozing. Rahmat!
```

## 6. Production

Ruxsat kelgach: **Production → Create release** → AAB → **Countries: Uzbekistan** (va kerakli boshqalar) →
**Review and roll out**. Birinchi tekshiruv bir necha kundan bir haftagacha davom etishi mumkin.

## 7. Daromad tizimi va onlayn to'lovlar

Google Play qoidasi: ilova ichidagi **raqamli** imkoniyat uchun pul faqat Google Play Billing orqali olinadi. **Jismoniy**
xizmatlar (yuk tashish, yetkazib berish) uchun bu talab yo'q — ular uchun Click, Payme va Paynet ishlatsa bo'ladi.
Telegram ham Mini App'larda raqamli xizmat uchun faqat Telegram Stars'ni talab qiladi, jismoniy xizmatga esa boshqa
to'lov tizimlariga ruxsat beradi.

Ilova buni o'zi hisobga oladi:
- **Komissiya** — yuk tashish xizmatiga tegishli. Balansni Click, Payme yoki Paynet orqali to'ldirish ilovada ham,
  Telegram'da ham, saytda ham ishlaydi.
- **Pullik «Shoshilinch» e'lon** — raqamli imkoniyat. Android ilovada va Telegram ichida u **ko'rsatilmaydi**
  (sayt ilovani `YukGoApp` belgisi, Telegram'ni Mini App ma'lumotidan taniydi). Bepul birinchi «Shoshilinch» qoladi.
  Oddiy saytda pullik variant ishlayveradi.
- Ilovada «Shoshilinch'ni saytda sotib oling» degan yozuv yoki havola **qo'ymang** — Google buni ham taqiqlaydi.

Daromad tizimini yoqqaningizda Play Console'da:
- **Data safety** — «Financial info → Purchase history» qatori yuqoridagi jadvalda bor, o'zgartirish shart emas.
- **Financial features** — ichki balans faqat YukGo xizmat haqi uchun, uni boshqa odamga o'tkazib yoki naqd qilib
  bo'lmaydi. Shuning uchun *My app doesn't provide any financial features* javobi qolishi mumkin. Google boshqacha
  so'rasa, Play Console yordam xizmatiga yozing.
- **Content rating** — «raqamli xaridlar»: **Yo'q** (ilovada pullik raqamli imkoniyat ko'rsatilmaydi).
- Store listing'ga «Balansni Click, Payme yoki Paynet orqali to'ldirish» qatorini qo'shishingiz mumkin.

## Yangilanishlar

- Faqat sayt o'zgarsa — Railway'ga push kifoya, ilova o'zi yangilanadi.
- Ilovaning o'zi o'zgarsa — `versionCode` ni oshirib, yangi AAB ni yuklang (README.md, 4-qadam).
