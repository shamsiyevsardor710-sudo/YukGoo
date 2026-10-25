# YukGo — yuk yetkazib berish platformasi

Viloyatdan viloyatga, shahardan shaharga va shahar ichida yuk yetkazish uchun veb-sayt, Telegram bot va REST API (mobil ilova uchun tayyor).

**Texnologiyalar:** Java 21, Spring Boot 3.3, Spring Security (JWT), Spring Data JPA, PostgreSQL (ishlab chiqarish) / H2 (sinov), Telegram Bot API, Yandex Maps JS API 2.1, oddiy JavaScript (freymvorksiz SPA).

---

## Imkoniyatlar

### Ro'yxatdan o'tish va kirish
- Telefon raqam → **Telegram bot orqali 6 xonali kod** → ism, familiya, parol va to'liq manzil (viloyat, tuman, qishloq/mahalla, uy).
- O'zbekistonning 14 ta hududi va barcha tumanlari bazaga avtomatik yoziladi.
- Kirish: **ism + familiya + parol** (bir xil ismli odamlar bo'lsa — telefon + parol).
- Parolni tiklash va telefon raqamni almashtirish ham Telegram kod orqali.
- Parolni taxmin qilishdan himoya (10 ta xatodan keyin 15 daqiqa blok).

### Profil
- Rasm yuklash, reyting (⭐ o'rtacha va taqsimot), bergan buyurtmalar va yetkazgan yuklar soni.
- **«Tahrirlash»** tugmasi ichida: ism-familiya, manzil, o'zi haqida, telefon raqam, parol.
- Mashina ma'lumotlari (marka, model, davlat raqami, kuzov turi, yuk ko'tarish, hajm, rasm) — **hammaga ko'rinadi**.
- Telefon raqam profilda turadi — boshqalar qo'ng'iroq qila oladi.
- Ommaviy profil sahifasi: boshqa foydalanuvchilar mashina, reyting va sharhlarni ko'radi.

### Logistika
- **Buyurtma**: yuk turi, og'irlik, hajm, kerakli mashina, rasm, olish/topshirish manzili (xaritadan belgilanadi), narx yoki «narx kelishiladi», to'lov usuli, kim to'laydi, shoshilinch belgisi, qabul qiluvchi.
- **Yo'nalish turi avtomatik**: shahar ichida / shahardan shaharga / viloyatlararo.
- **Narx hisoblagich**: Yandex marshrut masofasi va og'irlikka qarab taxminiy narx.
- **Bozor**: filtrlar (qayerdan, qayerga, yuk turi, masofa, mashina turi, qidiruv), ro'yxat yoki xaritada ko'rish.
- **Takliflar (auksion)**: haydovchilar narx taklif qiladi, yuboruvchi reyting va mashinaga qarab tanlaydi. Yoki haydovchi e'londagi narxga rozi bo'lib darhol oladi.
- **Holatlar**: Haydovchi kutilmoqda → Haydovchi tanlandi → Yo'lda → Yetkazildi (yoki Bekor qilingan) + to'liq tarix.
- **Topshirish kodi**: 4 xonali kod faqat yuboruvchiga ko'rinadi; haydovchi yukni faqat qabul qiluvchidan olingan kod bilan topshira oladi. Qabul qiluvchi Telegramga ulangan bo'lsa, kod unga bot orqali ham boradi.
- **Jonli kuzatuv**: haydovchining GPS joylashuvi yuboruvchi xaritasida yangilanib turadi.
- **Yozishma** yuboruvchi ↔ haydovchi (Telegramga ham boradi).
- **Baholash**: yetkazilgandan keyin ikki tomon bir-birini baholaydi; reyting profilda.
- **Reyslar**: haydovchi «Ertaga Toshkent → Samarqand, 2 t bo'sh joy» deb e'lon qiladi.
- **Haydovchilar katalogi**: avval tekshirilgan haydovchilar, keyin reyting va yetkazgan yuklar bo'yicha.
- **«Tekshirilgan haydovchi»**: haydovchi profilda guvohnoma va texpasport rasmini yuboradi (ilova rasmni ~1600px gacha kichraytiradi),
  admin «Tekshiruv» bo'limida ko'rib tasdiqlaydi yoki sababini yozib rad etadi. Rasmlar bazada saqlanadi, faqat adminga ko'rinadi va
  qaror qilingan zahoti o'chiriladi. Davlat raqami o'zgarsa yoki mashina o'chirilsa — belgi olinadi.
- Haydovchi voz kechsa buyurtma avtomatik qayta e'lon qilinadi; 30 kun olinmagan e'lonlar avtomatik yopiladi.
- Shikoyat / adminga murojaat.

### Haydovchilar uchun
- **Ilova ichidagi navigatsiya** (Yandex xarita sayt ichida ishlaydi, Yandex ilovasiga o'tib ketmaydi): joriy joylashuvdan olish nuqtasigacha, keyin topshirish nuqtasigacha marshrut, qolgan masofa va yetib borish vaqti, tirbandlik qatlami, «meni kuzatish» rejimi, ekran o'chmasligi.
- Navigatsiya ekranidan to'g'ridan-to'g'ri: qo'ng'iroq, «Yukni oldim», «Topshirish (kod)».
- «Hozir bo'shman» rejimi, ishlaydigan viloyatlar, yangi mos buyurtmalar Telegramga (kuzov turi va yuk ko'tarishga qarab filtrlanadi).

### Telegram bot
- Tasdiqlash kodlarini yuboradi.
- Barcha bildirishnomalar: yangi taklif, haydovchi tanlandi, yuk yo'lda, yetkazildi, yangi baho, yozishma, yangi buyurtmalar.
- Menyu: 📦 Buyurtmalarim, ⭐ Profilim, 🔑 Kod olish, ℹ️ Yordam.
- Pastdagi **«YukGo»** tugmasi saytni Telegram ichida (Mini App) ochadi: bir marta parol bilan kirilgach, keyingi safar o'zi kiradi.
- **Matn bilan e'lon:** foydalanuvchi botga guruhga yozgandek yozadi — *«Toshkent - Samarqand, 5 tonna mebel, tent kerak, ertaga, 3 mln»*.
  Bot viloyat va tumanni (lotin, kirill, ruscha yozuv, «-dan/-ga» qo'shimchalari), og'irlik, narx, sana, kuzov va yuk turini tushunadi
  va to'ldirilgan formani Mini App'da ochadi. Telefon raqamlar formaga o'tmaydi.
- **Havolalar:** `t.me/BOT?start=o123_ch3` — kanal postidagi «Taklif berish» (buyurtma kartasi + ochish tugmasi), `start=new` — e'lon berish,
  `start=src_KOD` — manba (kanal, hamkor, varaqa, agent), `start=ref_ID` — do'st taklifi. Mini App `startapp=` havolalarini ham tushunadi.
- **Adminlar uchun ommaviy xabar:** admin yoki ega o'z Telegramidan botga rasm, video, dumaloq video, ovozli xabar, fayl,
  albom yoki matn yuboradi → bot «Hammaga yuborilsinmi?» deb so'raydi → «✅ Hammaga yuborish» bosilsa, xabar botga ulangan
  barcha faol foydalanuvchilarga xuddi shu ko'rinishda boradi, oxirida nechta odamga yetgani yoziladi. Oddiy foydalanuvchilar
  yuborgan rasm hech kimga tarqalmaydi. Matnli xabarni boshqaruv panelidagi «Ommaviy xabar» bo'limidan ham yuborish mumkin.

### Admin va ega (owner)
| Amal | Admin | Ega |
|---|---|---|
| Statistika, buyurtmalarni ko'rish va bekor qilish | ✅ | ✅ |
| Oddiy foydalanuvchini bloklash / blokdan chiqarish / chiqarib yuborish / tiklash | ✅ | ✅ |
| Murojaatlarni hal qilish, nomaqbul sharhni o'chirish | ✅ | ✅ |
| Telegram orqali ommaviy xabar (matn — paneldan; rasm, video, dumaloq video — botga yuborib) | ✅ | ✅ |
| Foydalanuvchini admin qilish / adminlikdan olish | ❌ | ✅ |
| **Adminni** bloklash va chiqarib yuborish | ❌ | ✅ |
| Egalikni boshqa odamga topshirish | ❌ | ✅ |
| Egaga nisbatan har qanday amal | ❌ | ❌ |
| Kanallar, manbalar, haydovchi hujjatlarini tekshirish | ✅ | ✅ |
| Daromad va bonus sozlamalari | ❌ | ✅ |

Har bir admin amali jurnalga yoziladi (kim, kimga, qachon, sabab).

### Foydalanuvchi jalb qilish
- **Kanallar** (Boshqaruv → Kanallar): botni kanal yoki guruhga admin qilib qo'shasiz («Xabar joylash» huquqi) — har yangi ochiq buyurtma
  u yerga avtomatik chiqadi (viloyat filtri bilan). Postda yuk egasining ismi va telefoni yo'q, «📩 Taklif berish» va «➕ Yuk e'lon qilish»
  tugmalari bor. Haydovchi topilsa post «✅ Haydovchi topildi», bekor qilinsa «❌ E'lon yopilgan» ga o'zgaradi.
  @BotFather'da Main Mini App yoqilsa, tugma ilovani bir bosishda ochadi (sozlama shu sahifada).
- **Manbalar** (Boshqaruv → Manbalar): har kanal, hamkor, varaqa va agent uchun kod (`?src=KOD`, `t.me/BOT?start=src_KOD`), QR va chop
  etiladigan varaqa/stiker (`/promo.html`). Hisobot: kelganlar, haydovchilar, yuk egalari, faollashganlar; eng ko'p taklif qilganlar.
- **Taklif havolasi** har foydalanuvchining profilida. Do'sti birinchi buyurtmasini yakunlasa — ikkalasiga bonus (Daromad sozlamasida).
- **Bonuslar** (Daromad): taklif bonusi, yangi haydovchiga birinchi N ta buyurtma komissiyasiz, birinchi «Shoshilinch» e'lon bepul.
- Marketing materiallari (postlar, hamkorlarga taklif, agentlar yo'riqnomasi, chop etiladigan PDF): `marketing/` papkasi.

---

## Ishga tushirish (lokal)

Talab: **JDK 21** va **Maven 3.9+**.

```bash
mvn spring-boot:run
```

Brauzerda: <http://localhost:8080>

- Standart holatda fayl ichidagi H2 baza ishlatiladi (`./data/`) — hech narsa o'rnatish shart emas.
- Telegram token berilmagan bo'lsa, tasdiqlash kodi **saytda ko'rsatiladi** (sinov rejimi) va logga yoziladi.
- Ega hisobi: telefon `+998900000000`, parol `owner12345` — `.env` orqali o'zgartiring!
- API hujjati (mobil ilova uchun): <http://localhost:8080/swagger-ui.html>

Testlar (to'liq oqim + admin huquqlari):
```bash
mvn test
```

### Telegram botni ulash
1. Telegramda **@BotFather** → `/newbot` → nom va username bering.
2. Tokenni oling va muhit o'zgaruvchilariga qo'ying:
   ```bash
   export TELEGRAM_BOT_TOKEN=123456:AA...
   export TELEGRAM_BOT_USERNAME=sizning_botingiz
   mvn spring-boot:run
   ```
3. Bot long-polling rejimida ishlaydi — webhook va ochiq server shart emas.

> **Muhim:** Telegram boti telefon raqamga o'zi birinchi bo'lib yoza olmaydi. Shuning uchun birinchi marta foydalanuvchi saytdagi tugma orqali botni ochib, «📱 Telefon raqamni yuborish» tugmasini bosadi. Bot raqam saytda kiritilgan raqamga mos kelishini tekshirib, kodni yuboradi. Keyingi safar kod darhol keladi.

### Yandex xarita kaliti
1. <https://developer.tech.yandex.ru> → «Получить ключ» → **JavaScript API и HTTP Геокодер**.
2. Kalitni `YANDEX_MAPS_API_KEY` ga qo'ying. Kabinetda kalitni sayt domeningiz bilan cheklab qo'ying (HTTP Referer).

---

## Serverga joylash (Docker)

```bash
cp .env.example .env      # qiymatlarni to'ldiring
docker compose up -d --build
```

PostgreSQL va ilova birga ko'tariladi. Rasmlar `uploads` volume'da saqlanadi. HTTPS uchun oldiga Nginx/Caddy qo'ying (geolokatsiya brauzerda faqat HTTPS'da ishlaydi).

Asosiy muhit o'zgaruvchilari: `TELEGRAM_BOT_TOKEN`, `TELEGRAM_BOT_USERNAME`, `YANDEX_MAPS_API_KEY`, `PUBLIC_URL`, `JWT_SECRET` (≥32 belgi), `APP_DEV_MODE=false`, `OWNER_*`, `DB_*`.

---

## Railway'ga joylash

> `SPRING_PROFILES_ACTIVE=prod` berilmasa, ilova konteyner ichidagi H2 faylida ishlaydi. Railway har deployda konteynerni
> yangidan yaratadi, shuning uchun foydalanuvchilar va buyurtmalar o'chib ketadi. Rasmlar ham Volume'siz o'chadi.
> Ilova bunday holatda logga `DIQQAT:` bilan boshlanadigan ogohlantirish yozadi.

1. **Tarif:** Hobby. Trial'da volume 0.5 GB, «Limited Trial»da esa tarmoq cheklangan.
2. **PostgreSQL:** loyihada **+ Create (+ New) → Database → PostgreSQL**. Xizmat nomi `Postgres` bo'lib qolsin.
3. **Rasmlar uchun Volume:** YukGoo xizmatiga Volume ulang (Ctrl+K → Volume yoki kanvasda o'ng tugma), mount path: **`/app/uploads`**.
4. **YukGoo → Variables:**

   | O'zgaruvchi | Qiymat |
   |---|---|
   | `SPRING_PROFILES_ACTIVE` | `prod` |
   | `DB_URL` | `jdbc:postgresql://${{Postgres.PGHOST}}:${{Postgres.PGPORT}}/${{Postgres.PGDATABASE}}` |
   | `DB_USER` | `${{Postgres.PGUSER}}` |
   | `DB_PASSWORD` | `${{Postgres.PGPASSWORD}}` |
   | `RAILWAY_RUN_UID` | `0` — ilova Volume'ga yoza olishi uchun (Docker obrazi oddiy foydalanuvchi bilan ishlaydi) |
   | `JWT_SECRET` | uzun tasodifiy qiymat (pastdagi buyruq). Berilmasa, ilova ishga tushmaydi |
   | `TELEGRAM_BOT_TOKEN`, `TELEGRAM_BOT_USERNAME` | @BotFather'dan |
   | `OWNER_PHONE`, `OWNER_FIRST_NAME`, `OWNER_LAST_NAME`, `OWNER_PASSWORD` | ega hisobi. Faqat bo'sh bazada bir marta yaratiladi, parolsiz yaratilmaydi |
   | `PUBLIC_URL` | `https://<sizning-domeningiz>` |
   | `APP_DEV_MODE` | `false` |
   | `YANDEX_MAPS_API_KEY`, `SUPPORT_EMAIL` | ixtiyoriy |

   `JWT_SECRET` yaratish (PowerShell):
   ```powershell
   $b = New-Object byte[] 48; [Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($b); [Convert]::ToBase64String($b)
   ```
5. **Deploy** va logni tekshiring:
   - `HikariPool-1 - Added connection org.postgresql…` chiqishi kerak (`jdbc:h2` emas);
   - `Telegram bot @… ishga tushdi` chiqishi kerak;
   - `DIQQAT:` bilan boshlanadigan qatorlar bo'lmasligi kerak.

---

## Mobil ilova uchun API

Barcha so'rovlar `Authorization: Bearer <token>` bilan. Asosiy endpointlar:

| Bo'lim | Endpoint |
|---|---|
| Ro'yxatdan o'tish | `POST /api/auth/register/start` → `POST /api/auth/verify` → `POST /api/auth/register/complete` |
| Kirish | `POST /api/auth/login` `{firstName, lastName, password}` yoki `{phone, password}` |
| Parolni tiklash | `POST /api/auth/reset/start`, `POST /api/auth/reset/complete` |
| Hududlar | `GET /api/locations/regions`, `GET /api/locations/regions/{id}/districts` |
| Profil | `GET/PUT /api/users/me`, `POST /api/users/me/avatar`, `PUT /api/users/me/vehicle`, `PUT /api/users/me/settings`, `GET /api/users/{id}` |
| Buyurtmalar | `GET /api/orders` (bozor), `POST /api/orders`, `GET /api/orders/my`, `GET /api/orders/{id}` |
| Takliflar | `POST /api/orders/{id}/offers`, `POST .../offers/{offerId}/accept`, `POST /api/orders/{id}/take` |
| Holat | `POST /api/orders/{id}/pickup`, `POST /api/orders/{id}/deliver {code}`, `POST /api/orders/{id}/cancel` |
| Kuzatuv | `POST /api/orders/{id}/track {lat,lng}`, `GET /api/orders/{id}/track` |
| Yozishma | `GET/POST /api/orders/{id}/messages` |
| Baho | `POST /api/orders/{id}/review`, `GET /api/users/{id}/reviews` |
| Reyslar | `GET/POST /api/trips`, `GET /api/trips/my` |
| Taklif | `GET /api/users/me/referral` |
| Haydovchi tekshiruvi | `GET/POST /api/users/me/driver-verification` (multipart: `license`, `techPassport`) |
| Admin | `/api/admin/**` (stats, users, orders, reports, broadcast, logs, channels, sources, driver-verifications) |

To'liq sxema: `/swagger-ui.html`. Mobil ilovada Yandex MapKit SDK (Android/iOS) dan foydalanish tavsiya etiladi.

---

## Loyiha tuzilishi

```
src/main/java/uz/yukgo/
  auth/          ro'yxatdan o'tish, kirish, Telegram kod bilan tasdiqlash
  telegram/      bot (long polling), Telegram API mijoz, matndan e'lonni tushunish (LoadTextParser)
  channel/       kanallarga avtomatik e'lon va postlarni yangilash
  growth/        manba kodlari, taklif havolalari va bonuslar
  driver/        «Tekshirilgan haydovchi» arizalari
  user/          foydalanuvchi, profil, mashina
  order/         buyurtma, takliflar, holatlar, GPS kuzatuv, yozishma, narx hisoblagich
  trip/          haydovchi reyslari
  review/        baholash va reyting
  admin/         admin/ega paneli, shikoyatlar, jurnal
  notification/  sayt ichidagi va Telegram bildirishnomalari
  location/      viloyat va tumanlar
  security/      JWT, Spring Security
src/main/resources/static/   veb-sayt (SPA): index.html, css/, js/; promo.html — chop etiladigan varaqa va stiker
marketing/                   jalb qilish rejasi, kanal postlari, hamkor va agentlar uchun materiallar
```
