# YukGo — yuk, pochta va yo'lovchi tashish platformasi

Viloyatdan viloyatga, shahardan shaharga va shahar ichida yuk, pochta (kichik jo'natma) va yo'lovchi tashish uchun veb-sayt,
Telegram bot va REST API (Android ilova shu saytni ochadi). Haydovchilar reys e'lon qiladi — mos e'lonlar haqida xabar o'zi keladi.

**Texnologiyalar:** Java 21, Spring Boot 3.3, Spring Security (JWT), Spring Data JPA, PostgreSQL (ishlab chiqarish) / H2 (sinov), Telegram Bot API, Yandex Maps JS API 2.1, oddiy JavaScript (freymvorksiz SPA).

---

## Imkoniyatlar

### Ro'yxatdan o'tish va kirish
- Telefon raqam → **SMS orqali 6 xonali kod** (SMS sozlanmagan bo'lsa — Telegram bot orqali) → ism, familiya, parol, **viloyat va tuman**
  (qishloq va uy manzili so'ralmaydi, parol bir marta — «ko'rsatish» tugmasi bilan) → **foydalanish shartlari va maxfiylik siyosatiga rozilik** (belgi).
- Ro'yxatdan o'tgach bitta savol: «Men haydovchiman» (mashina qo'shishga) yoki «E'lon beraman» (yangi e'lon formasiga).
- Shartlar paydo bo'lishidan oldin ro'yxatdan o'tganlarga ilova kirganda bir marta shartlarni ko'rsatadi («Qabul qilaman»). Shartlar jiddiy o'zgarsa — `Terms.VERSION` oshiriladi va hamma qayta qabul qiladi.
- **Telefon raqam maydonlari**da «+998» o'zi yoziladi — faqat qolgan 9 ta raqam kiritiladi (`90 123 45 67`); yozilayotganda raqam
  `+998 90 123 45 67` ko'rinishiga keltiriladi. «+998 90…», «998…» yoki «90…» ko'rinishida qo'yilgan (nusxalangan) raqamni ham tushunadi.
- O'zbekistonning 14 ta hududi va barcha tumanlari bazaga avtomatik yoziladi.
- Kirish: **ism + familiya + parol** (bir xil ismli odamlar bo'lsa — telefon + parol).
- Parolni tiklash va telefon raqamni almashtirish ham shu kod orqali.
- Parolni taxmin qilishdan himoya (10 ta xatodan keyin 15 daqiqa blok).

### Profil
- Rasm yuklash, reyting (⭐ o'rtacha va taqsimot), bergan buyurtmalar va yetkazgan yuklar soni.
- **«Tahrirlash»** tugmasi ichida: ism-familiya, viloyat va tuman, o'zi haqida, telefon raqam, parol.
- **Sozlamalar** (`#/settings`, Profil → «Sozlamalar»): hammasi bitta sahifada, guruhlarga bo'lingan — **Haydovchi** («Hozir bo'shman»,
  yangi e'lonlar haqida xabar, «Faqat reyslarimga mos e'lonlar», e'lon turlari, ishlaydigan viloyatlar, «Mening reyslarim»),
  **Bildirishnomalar** (Telegram ulash/uzish, 🔔 ro'yxati), **Hisob** (ism va manzil, telefon, parol), **Ko'rinish** (kechki/kunduzgi),
  **Maxfiylik va hujjatlar** (Bloklanganlar, shartlar, maxfiylik siyosati), pastda «Chiqish» va «Hisobni o'chirish».
  Profilning o'zi qisqa: rasm va reyting, mashina, balans, taklif havolasi, «Sozlamalar» qatori (haydovchiga — hozirgi holati bilan)
  va «Biz bilan bog'lanish».
- **Mashina**: nomi (masalan «Chevrolet Cobalt»), davlat raqami, turi — 6 tadan biri: yuk mashinasi, yengil mashina, ochiq bortli,
  furgon, konteyner tashuvchi, boshqa — ixtiyoriy rangi va rasmi. Yuk ko'tarish, hajm, yil so'ralmaydi. **Hammaga ko'rinadi**.
  Eski turlar (tentli, refrijerator, samosval, Damas/Labo…) ishga tushishda avtomatik shu 6 tadan biriga o'tkaziladi.
- **Kunduzgi va kechki rejim**: yuqoridagi quyosh/oy tugmasi (va Sozlamalar → Ko'rinish). Birinchi kirishda — kechki; tanlov shu qurilmada eslab qolinadi.
- Telefon raqam profilda turadi — boshqalar qo'ng'iroq qila oladi.
- Ommaviy profil sahifasi: boshqa foydalanuvchilar mashina, reyting va sharhlarni ko'radi.

### Logistika
- **E'lon turlari**: 📦 **Yuk** (nima, ixtiyoriy og'irlik va mashina turi), ✉️ **Pochta** (hujjat, kichik quti) va 🧍 **Yo'lovchi**
  (faqat qayerdan, qayerga, qachon va necha kishi). Hammasida: viloyat va tuman, «Bugun / Ertaga» yoki kalendardan boshqa kun, ixtiyoriy narx
  (bo'sh — «narx kelishiladi»; yo'lovchida — bir kishi uchun), ixtiyoriy izoh. Yuk va pochtada qabul qiluvchi — «O'zim» yoki
  «Boshqa odam» (telefoni). Aniq manzil, xarita va rasm — yig'ilgan «Aniq manzil va xarita (ixtiyoriy)» bo'limida.
  To'lov usuli, kim to'lashi, hajm va yuk turi endi so'ralmaydi.
- **Yo'nalish turi avtomatik**: shahar ichida / shahardan shaharga / viloyatlararo.
- **Narx hisoblagich**: Yandex marshrut masofasi va og'irlikka qarab taxminiy narx.
- **Bosh sahifa (E'lonlar)**: kirgan foydalanuvchiga **avval o'z tumanidan, keyin o'z viloyatidan, so'ng yaqin viloyatlardan**
  chiqqan e'lonlar (ro'yxatdan o'tishda ko'rsatgan manzili bo'yicha); har birining ichida shoshilinchlar tepada, keyin sana bo'yicha.
  Qidiruv: «Qayerdan» va «Qayerga» — viloyat, keyin tuman; tur bo'yicha: Hammasi · Yuk · Pochta · Yo'lovchi.
  «Shoshilinch» — faqat daromad tizimi yoqilganda (aks holda formada ham, e'lon sahifasida ham ko'rinmaydi).
- **Kirmaganga faqat kirish va ro'yxatdan o'tish**: mehmon (saytda, Android ilovada, Telegram Mini App'da) bosh oynani — qisqa tavsif,
  «Ro'yxatdan o'tish», «Kirish» va umumiy sonlar (`#/welcome`) — ko'radi; shu qurilmada avval kirgan bo'lsa (chiqib ketgan yoki sessiyasi
  tugagan) — darhol kirish sahifasi. E'lonlar, reyslar, haydovchilar va profillar faqat kirganlarga
  (API ham: `/api/orders`, `/api/trips`, `/api/drivers`, `/api/users/{id}` — kirishsiz 401; ochiq qolgani — `/api/stats/public`).
  Ulashilgan havola (`#/orders/123`), botdan kelgan to'ldirilgan forma va boshqa sahifalar ham avval shu oynani ochadi, kirgach yoki ro'yxatdan
  o'tgach o'sha sahifaning o'zi ochiladi. Google uchun yo'nalish sahifalari (`/yo/...`) avvalgidek ochiq (ism va telefonsiz).
  **«Ulashish»** tugmasi: Telegram ichida — Telegram «Ulashish» oynasi (bot havolasi bilan), telefonda — tizimning ulashish oynasi,
  kompyuterda — havola nusxalanadi. Reyslarni ham («Mening reyslarim») ulashish mumkin.
- **Takliflar (auksion)**: haydovchilar narx taklif qiladi, yuboruvchi reyting va mashinaga qarab tanlaydi. Yoki haydovchi e'londagi narxga
  rozi bo'lib darhol oladi. Pochta va yo'lovchida narx ko'rsatilmagan bo'lsa ham **«Olaman»** — ikki tomonga bir-birining raqami ochiladi,
  narxni telefonda kelishadi.
- **Holatlar**: Haydovchi kutilmoqda → Haydovchi tanlandi → Yo'lda → Yetkazildi (yoki Bekor qilingan) + to'liq tarix.
- **Mening buyurtmalarim**: «E'lonlarim» (men bergan) va «Hammasi» (bergan, olgan va narx taklif qilgan — har birida belgi bilan),
  har birida «Faol» va «Tugatilgan».
- **Topshirish kodi**: 4 xonali kod faqat yuboruvchiga ko'rinadi; haydovchi yuk va pochtani faqat qabul qiluvchidan olingan kod bilan topshira oladi. Qabul qiluvchi Telegramga ulangan bo'lsa, kod unga bot orqali ham boradi. Yo'lovchida kod yo'q: «Yo'lovchini oldim» → «Manzilga yetib keldik».
- **Jonli kuzatuv**: haydovchining GPS joylashuvi yuboruvchi xaritasida yangilanib turadi.
- **Yozishma** yuboruvchi ↔ haydovchi — xabarlar faqat saytdagi yozishmada turadi (Telegramga ham, 🔔 ga ham yuborilmaydi).
  **«💬 Xabarlar»** (`#/messages`, tepadagi 💬 belgisi qizil raqam bilan): barcha yozishmalar bir joyda — har birida suhbatdosh,
  yo'nalish, oxirgi xabar va o'qilmaganlar soni; yozishma ochilganda o'qilgan hisoblanadi (o'qilganlik `message_reads` jadvalida;
  16-paketgacha yozilgan xabarlar birinchi ishga tushishda bir marta «o'qilgan» deb belgilanadi). Ro'yxatda eng yangi 50 ta
  yozishma va o'qilmagani bor hammasi.
  Android ilovasi bo'lsa — yangi xabar telefonga push bo'lib keladi. Haydovchi **«Olaman» dan oldin ham** yoza oladi («E'lon egasiga yozish»):
  har bir haydovchi bilan alohida yozishma, e'lon egasi sahifasida «Yozishmalar» ro'yxati (kim yozgan, «Javob kutmoqda»).
  Shu vaqtda ikki tomonning ham telefon raqami yashirin, xabarga yozilgan raqam ham `[raqam «Olaman»dan keyin ko'rinadi]` bo'lib
  ko'rinadi (Telegram @username va t.me havolalari ham); e'lon shu haydovchiga berilgach — raqamlar ochiladi, qolgan haydovchilar
  bilan yozishma yopiladi (o'qish mumkin). Bildirishnoma havolasi `#/orders/ID?chat=HAYDOVCHI_ID` — shu yozishmani ochadi.
- **Baholash**: yetkazilgandan keyin ikki tomon bir-birini baholaydi; reyting profilda.
- **Reyslar**: haydovchi «Ertaga 07:00 Kosonsoy → Toshkent, 3 ta bo'sh o'rin» yoki **«Har kuni qatnayman»** deb e'lon qiladi
  (bo'sh o'rin — yengil mashina, furgon va «boshqa» turida; narx va izoh — ixtiyoriy). Ro'yxat ham yaqinlik bo'yicha, viloyat → tuman qidiruvi bilan.
- **Avtomatik moslash** (`trip/MatchService`):
  - yangi e'lon → shu yo'nalishda (viloyatdan viloyatga), o'sha kuni yoki ertasi (yoki «har kuni») jo'naydigan reysi bor haydovchilarga
    «🎯 Siz uchun yangi yo'lovchi / pochta / buyurtma» (ilova ichida ham, Telegramda ham; boshqa tumandan bo'lsa — «Yo'lingizda»;
    reysga yana boshqa e'lonlar ham sig'sa — «💡 Reysingizga endi 2 yo'lovchi (3 kishi) va 1 ta pochta mos…» va «🧩 Reysni yig'ish» tugmasi);
    so'ng uyi yoki ishlaydigan viloyati e'lon yo'nalishida bo'lgan bo'sh haydovchilarga Telegram orqali (o'z tumanidagilarga
    «📍 Tumaningizdan» belgisi bilan, avval ular);
  - yangi reys → shu yo'nalishda haydovchi kutayotgan e'lon egalariga «🚗 Yo'nalishingizda haydovchi bor» (ism, mashina, telefon);
    bitta haydovchining reyslari haqida bir odamga 12 soatda bir martadan ortiq xabar bormaydi; haydovchiga mos e'lonlar soni ko'rsatiladi;
  - haydovchi qaysi e'lonlar haqida xabar olishini tanlaydi (Sozlamalar → Haydovchi): standart — yengil mashina: yo'lovchi va pochta,
    yuk mashinalari: yuk va pochta, furgon va boshqalar: hammasi. Mashina turi so'ralgan yuk mos mashinaga boradi; blok bo'lganlarga bormaydi.
- **«Reysga yig'ish»** (`trip/TripPlanner`, `#/trips/ID/plan`, `GET /api/trips/{id}/plan`): haydovchi «8-noyabr, Kosonsoy → Toshkent,
  4 o'rin» deb reys e'lon qilsa, shu yo'nalishdagi (viloyatdan viloyatga) o'sha kunga — yoki sanasiz, so'nggi 3 kunda berilgan — ochiq
  e'lonlardan bitta reysda olib ketiladigan to'plamni tavsiya qiladi: bo'sh o'rinlarni eng ko'p to'ldiradigan yo'lovchilar (masalan,
  4 o'ringa 3 + 2 emas, 2 + 2; reysdagi «bo'sh o'rinlar» — yozilgan paytdagisi: undan keyin olingan yo'lovchilar ayiriladi, oldin
  olinganlari esa allaqachon hisobga olingan; o'rinlar ko'rsatilmagan bo'lsa — mashina turiga qarab: yengil 4, furgon 6, yuk mashinasi 2)
  va **2 tagacha pochta**. Avval reys tumanidan va boradigan tumanidan, keyin birinchi berilgani.
  «Mening reyslarim»da reys tagida «Reysga yig'ish: 2 yo'lovchi (3 kishi) · 1 pochta mos», sahifada — o'rinlar sxemasi va har bir e'londa
  «Olaman». Mos e'lon bo'lmasa — hech narsa chiqmaydi. Reys kuni ertalab 07:00 da «☀️ Xayrli tong! Bugungi reysingizga mos e'lonlar bor»
  xabari keladi (ertalabki xulosa o'rniga), yangi mos e'lon tushganda ham «💡 Reysingizga endi … mos» deyiladi.
- **Pochta chegarasi** (`order/OrderRules`): bitta haydovchi bir vaqtda ko'pi bilan **2 ta pochta** oladi (olgan, hali yetkazmagan;
  yetkazgach yoki bekor bo'lgach yana oladi) — qolgan pochtalar boshqa haydovchilarga ham qolsin. «Olaman», narx taklifi va taklifni
  qabul qilishda tekshiriladi; chegaraga yetgan haydovchiga yangi pochta haqida xabar ham bormaydi. Ega **Boshqaruv → Buyurtmalar → «Qoidalar»** da o'zgartiradi (0 — cheklovsiz, 50 gacha; `GET/PUT /api/admin/rules`).
- E'lon egasi sahifasida **«Yo'nalishingizdagi haydovchilar»**: shu yo'nalishda reys e'lon qilganlar, qo'ng'iroq tugmasi bilan.
- **Haydovchilar katalogi**: avval o'z tumani va viloyatidagilar, so'ng yaqin viloyatlardagilar; har birining ichida — tekshirilgan
  haydovchilar, reyting va bajargan buyurtmalar bo'yicha.
- **«Tekshirilgan haydovchi»**: haydovchi profilda guvohnoma va texpasport rasmini yuboradi (ilova rasmni ~1600px gacha kichraytiradi),
  admin «Tekshiruv» bo'limida ko'rib tasdiqlaydi yoki sababini yozib rad etadi. Rasmlar bazada saqlanadi, faqat adminga ko'rinadi va
  qaror qilingan zahoti o'chiriladi. Davlat raqami o'zgarsa yoki mashina o'chirilsa — belgi olinadi.
- Haydovchi voz kechsa buyurtma avtomatik qayta e'lon qilinadi; 30 kun olinmagan e'lonlar avtomatik yopiladi.
- **Sanasi o'tgan e'lon** (`order/OrderReminders`): ertasi kuni soat 09:00 da egasiga «📅 E'loningiz sanasi o'tdi — hali kerakmi?»
  (🔔, push va Telegramda «📅 Bugunga», «📅 Ertaga», «❌ Kerak emas» tugmalari). E'lon sahifasida ham «Bugunga ko'chirish» / «Ertaga»
  (`POST /api/orders/{id}/reschedule`). Ko'chirilgan e'lon yana yangilar qatorida turadi va haydovchilarga qayta xabar boradi.
- **Ertalabki xulosa**: har kuni 07:00 da haydovchiga «☀️ Bugun va ertaga yo'nalishlaringizda 7 ta ochiq e'lon bor» — turi va eng ko'p
  yo'nalishlar bilan (yangi e'lonlar haqidagi xabar yoqilgan va «Hozir bo'shman» bo'lsa; Telegramga, Telegrami yo'q bo'lsa — push).
  Shu kuni jo'naydigan reysi bo'lsa va unga mos e'lonlar bo'lsa — umumiy xulosa o'rniga «Reysga yig'ish» xabari (ko'pi bilan 2 ta reys uchun;
  «Bandman» bo'lsa ham keladi — reysga mos xabarlar doim keladi, faqat umumiy xulosa kelmaydi).
- **Qayta joylash**: e'lon sahifasida «🔁 Qayta joylash» (`#/new?copy=ID`) — forma o'sha e'lon bilan, bugungi sana bilan to'ldiriladi.
- **Narx maslahati**: yo'lovchi va pochta formasida «💡 Bu yo'nalishda odatda 120 000 – 150 000 so'm (1 kishi uchun)» va «… qo'yish»
  tugmasi (`GET /api/orders/price-hint`): so'nggi 4 oydagi shu yo'nalishdagi (ikkala tomonga) e'lonlar narxining o'rtadagi yarmi,
  kamida 3 ta e'lon bo'lsa; viloyat ichida — faqat aynan shu tumanlar bo'yicha.
- **Yo'nalish sahifalari (Google uchun)**: `/yo` — barcha yo'nalishlar, `/yo/toshkent-shahri/samarqand` — shu yo'nalishdagi ochiq e'lonlar
  va reyslar (serverda tayyor HTML, telefon va ismsiz), «E'lon berish» tugmalari to'ldirilgan forma bilan. `/sitemap.xml` va `/robots.txt` —
  Google Search Console'ga `https://SAYT/sitemap.xml` ni qo'shing.
- Shikoyat / adminga murojaat: profil va buyurtma sahifasida (buyurtma tomonlari ham bir-biri haqida), istasa — shikoyat bilan birga bloklash.
- **«Biz bilan bog'lanish»** (Profil → «Biz bilan bog'lanish», `#/support`): haydovchi yoki foydalanuvchi taklif, muammo, savol yoki boshqa
  xabar yozadi (bir kunda ko'pi bilan 5 ta) yoki qo'ng'iroq qiladi (Railway'da `SUPPORT_PHONE` berilgan bo'lsa — kirish sahifasida ham).
  Ega va faol adminlarga darhol 🔔, Telegram va push. Ular **Profil → Murojaatlar → «Haydovchilardan» / «Foydalanuvchilardan»**
  (`#/support/inbox`) yoki **Boshqaruv → Aloqa** da ko'radi: muallif ismi, telefoni (qo'ng'iroq tugmasi), manzili, mashinasi; «Javob yozish» —
  javob muallifga bildirishnoma bo'lib boradi va uning «Mening murojaatlarim» ro'yxatida chiqadi; «Yopish» — yangilar ro'yxatidan chiqadi.
  Hisob o'chirilsa, murojaatlari ham o'chiriladi.
- **🔔 Bildirishnomalar**: «Hammasini o'qilgan qilish» va tagida «Hammasini o'chirish» (tasdiqlash bilan; faqat bildirishnomalar ro'yxati
  tozalanadi — buyurtma, taklif va yozishmalar o'chmaydi).
- **Bloklash** (Google Play talabi — 1:1 yozishma bor): bloklangan foydalanuvchi bilan bir-biringizga yoza olmaysiz, taklif bera/qabul qila
  olmaysiz, e'lonlar, reyslar va haydovchilar ro'yxatida bir-biringizni ko'rmaysiz, yangi e'lon va mos reys xabari bormaydi; ochiq takliflar yopiladi.
  Boshlangan buyurtma davom etadi, faqat yozishma yopiladi. Ro'yxat va blokdan chiqarish: Sozlamalar → Bloklanganlar.
- Hujjatlar: `/terms.html` (foydalanish shartlari: taqiqlangan kontent, shikoyat, bloklash), `/privacy.html`, `/delete-account.html` —
  ilova ichida oynada ochiladi.

### Hamyon va to'lovlar
- Daromad tizimi yoqilganda (Boshqaruv → Daromad) haydovchiga buyurtma berilganda komissiya, «Shoshilinch» e'lon uchun to'lov balansdan yechiladi.
- **Balansni to'ldirish**: profilda «Balansni to'ldirish» → summa → **Click** yoki **Payme** (to'lov sahifasi ochiladi) yoki **Paynet**
  (Paynet ilovasi yoki shoxobchasida hisob raqami sifatida YukGo ID kiritiladi). Pul tushishi bilan balans yangilanadi va xabar keladi.
- Ega balansni qo'lda ham to'ldira oladi; onlayn to'lovlar ro'yxati va holati — Boshqaruv → Daromad → «Onlayn to'lovlar».

### Haydovchilar uchun
- **Ilova ichidagi navigatsiya** (Yandex xarita sayt ichida ishlaydi, Yandex ilovasiga o'tib ketmaydi): joriy joylashuvdan olish nuqtasigacha, keyin topshirish nuqtasigacha marshrut, qolgan masofa va yetib borish vaqti, tirbandlik qatlami, «meni kuzatish» rejimi, ekran o'chmasligi.
- Navigatsiya ekranidan to'g'ridan-to'g'ri: qo'ng'iroq, «Yukni oldim» / «Yo'lovchini oldim», «Topshirish (kod)» / «Yetib keldik».
- «Hozir bo'shman» rejimi, xabar olinadigan e'lon turlari, ishlaydigan viloyatlar (o'z viloyati doim hisobga olinadi), yangi mos e'lonlar
  Telegramga (Telegrami ulanmagan, Android ilovadagi haydovchiga — push).
- **«Faqat reyslarimga mos e'lonlar»** (Sozlamalar → Haydovchi): yoqilsa, yangi e'lon haqida faqat «Reyslar»da e'lon qilgan
  yo'nalishiga (viloyatdan viloyatga, sanasi va bo'sh joylari mos) xabar keladi; uyi va ishlaydigan viloyatlari bo'yicha xabarlar va ertalabki
  xulosadagi boshqa e'lonlar — yo'q. Faol reysi bo'lmasa, Sozlamalarda «xabar kelmaydi» ogohlantirishi va «Reys e'lon qilish» havolasi chiqadi.

### Telegram bot
- Tasdiqlash kodlarini yuboradi.
- Barcha bildirishnomalar: yangi taklif, haydovchi tanlandi, yuk yo'lda, yetkazildi, yangi baho, yangi mos e'lonlar va yo'nalishdagi haydovchilar (yozishmadagi xabarlar — faqat saytda).
- **Rasmli bildirishnomalar** (`notification/CardRenderer`, `TelegramCards`): yangi e'lon, «Haydovchi topildi», «Oq yo'l!», «Yetkazildi»,
  ertalabki xabar va «Reysga yig'ish» Telegramga kartochka-rasm bilan keladi — katta sarlavha, yo'nalish, sana, narx, o'rinlar
  (shriftlar — Onest va Unbounded, OFL litsenziyasi, `src/main/resources/fonts/`); rasm tagida qalin sarlavha va belgili qatorlardan
  iborat aniq matn va tugmalar. Bir xil rasm Telegramga qayta yuklanmaydi (`file_id` qayta ishlatiladi). Rasm yuborib bo'lmasa — xuddi
  shu matn oddiy xabar bo'lib boradi. Rasmsiz, faqat matn: Railway'da `TELEGRAM_CARDS=false`. Qolgan xabarlar (bekor qilish, haydovchi
  voz kechdi, taklif rad etildi, yangi baho — 4–5 yulduzda rasm bilan, balans, bonus, tekshiruv, sanasi o'tgan e'lon) ham bir xil tartibda:
  qalin sarlavha, 📍 yo'nalish, 📅 sana, 🧍/✉️ nima, 💬 sabab.
- **Guruhlarda**: botni Telegram guruhiga qo'shsangiz, u guruhdagi aniq e'lonni (*«Toshkentdan Samarqandga 2 kishi ertaga»*) ko'rib,
  muallifga «✅ YukGo'ga joylash» tugmasini taklif qiladi (`t.me/BOT?start=gp_…`). Muallif bossa — botga o'tadi va e'lon bir bosishda
  saytga chiqadi (ulanmagan bo'lsa — to'ldirilgan forma va raqam tugmasi, manba «guruh»). Savol va oddiy gaplarga javob bermaydi;
  bitta odamga bitta guruhda 6 soatda bir marta, guruhda 3 daqiqada ko'pi bilan bitta javob. Guruh matni bazaga yozilmaydi (24 soat xotirada).
  «Kanallar» bo'limida ulangan (hamkor) guruhlarda bot a'zolarga javob yozmaydi — u yerda faqat e'lon joylaydi.
  **Sozlash:** @BotFather → `/setprivacy` → botni tanlang → **Disable** (aks holda bot guruhdagi oddiy xabarlarni ko'rmaydi), so'ng
  botni guruhdan chiqarib, qayta qo'shing. Yoki botni guruh admini qiling.
- Menyu: 📦 Buyurtmalarim, ⭐ Profilim, 🔑 Kod olish, ℹ️ Yordam.
- Pastdagi **«YukGo»** tugmasi saytni Telegram ichida (Mini App) ochadi: bir marta parol bilan kirilgach, keyingi safar o'zi kiradi.
- **Matn bilan e'lon:** foydalanuvchi botga guruhga yozgandek yozadi — *«Toshkent - Samarqand, 5 tonna mebel, tent kerak, ertaga, 3 mln»*,
  *«Kosonsoydan Toshkentga 2 kishi, ertaga»* yoki *«Andijon - Toshkent, pochta, bugun»*. Bot viloyat va tumanni (lotin, kirill, ruscha yozuv,
  «-dan/-ga» qo'shimchalari), og'irlik, narx, sana, mashina va yuk turini, **yo'lovchi va kishilar sonini, pochtani** tushunadi.
  **Hisobi botga ulangan** (shartlarni qabul qilgan) foydalanuvchida e'lon **darhol saytga chiqadi** va mos haydovchilarga xabar
  boradi; javobda «👀 Ko'rish», «📤 Ulashish», «❌ Bekor qilish» tugmalari. Xuddi shu e'lon 30 daqiqa ichida qayta yuborilsa — yangisi
  ochilmaydi; botdan kuniga 10 tagacha e'lon. Ulanmagan foydalanuvchiga — to'ldirilgan forma (Mini App). Telefon raqamlar e'longa
  o'tmaydi. Haydovchining e'loni (*«Kosonsoy - Toshkent, ertaga 07:00, 3 ta joy bor»*, *«har kuni ... olaman»*) — reys darhol chiqadi
  (soat yozilmasa: bugun — 1 soatdan keyin, boshqa kun yoki «har kuni» — 08:00), «❌ O'chirish» tugmasi bilan; mashinasi kiritilmagan
  bo'lsa — reys formasi. Admin yozgan e'lonsimon matnda «📝 E'lon sifatida joylash» tugmasi ham chiqadi.
- **Havolalar:** `t.me/BOT?start=o123_ch3` — kanal postidagi «Taklif berish» (buyurtma kartasi + ochish tugmasi), `start=new` — e'lon berish,
  `start=src_KOD` — manba (kanal, hamkor, varaqa, agent), `start=ref_ID` — do'st taklifi. Mini App `startapp=` havolalarini ham tushunadi.
- **Adminlar uchun ommaviy xabar:** admin yoki ega o'z Telegramidan botga rasm, video, dumaloq video, ovozli xabar, fayl,
  albom yoki matn yuboradi → bot «Hammaga yuborilsinmi?» deb so'raydi → «✅ Hammaga yuborish» bosilsa, xabar botga ulangan
  barcha faol foydalanuvchilarga xuddi shu ko'rinishda boradi, oxirida nechta odamga yetgani yoziladi. Oddiy foydalanuvchilar
  yuborgan rasm hech kimga tarqalmaydi (bot ularga sababini aytadi). Matnli xabarni boshqaruv panelidagi «Ommaviy xabar» bo'limidan ham yuborish mumkin.
  **Muhim:** bot egani Telegram orqali taniydi — ega hisobi botga ulangan bo'lishi kerak. Baza yangidan boshlansa (masalan, PostgreSQL'ga
  o'tilganda) ega hisobi qayta yaratiladi: botga `/start` yozib, «📱 Telefon raqamni yuborish» ni bosing (raqam `OWNER_PHONE` bilan bir xil).

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
| Pochta chegarasi (Boshqaruv → Buyurtmalar → Qoidalar; admin faqat ko'radi) | ❌ | ✅ |

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

PostgreSQL va ilova birga ko'tariladi. Rasmlar `uploads` volume'da saqlanadi. Ilova porti (8080) faqat shu serverning
o'zida ochiq — oldiga Nginx/Caddy qo'ying va HTTPS ulang (geolokatsiya brauzerda faqat HTTPS'da ishlaydi). Proksi
`X-Real-IP` yoki `X-Forwarded-For` sarlavhasini yozsin: ilova SMS cheklovlari va to'lov tizimlari IP ro'yxati uchun
mijoz IP manzilini shundan oladi.

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
   | `DB_URL` | `jdbc:postgresql://${{Postgres.RAILWAY_PRIVATE_DOMAIN}}:5432/${{Postgres.PGDATABASE}}` — ichki (bepul) tarmoq. Eski Postgres xizmatlarida `PGHOST`/`PGPORT` ochiq proksiga qaraydi va egress uchun pul olinadi |
   | `DB_USER` | `${{Postgres.PGUSER}}` |
   | `DB_PASSWORD` | `${{Postgres.PGPASSWORD}}` |
   | `RAILWAY_RUN_UID` | `0` — ilova Volume'ga yoza olishi uchun (Docker obrazi oddiy foydalanuvchi bilan ishlaydi) |
   | `JWT_SECRET` | uzun tasodifiy qiymat (pastdagi buyruq). Berilmasa, ilova ishga tushmaydi |
   | `TELEGRAM_BOT_TOKEN`, `TELEGRAM_BOT_USERNAME` | @BotFather'dan |
   | `OWNER_PHONE`, `OWNER_FIRST_NAME`, `OWNER_LAST_NAME`, `OWNER_PASSWORD` | ega hisobi. Faqat bo'sh bazada bir marta yaratiladi, parolsiz yaratilmaydi |
   | `PUBLIC_URL` | `https://<sizning-domeningiz>` |
   | `APP_DEV_MODE` | `false` |
   | `YANDEX_MAPS_API_KEY`, `SUPPORT_EMAIL` | ixtiyoriy |
   | `SUPPORT_PHONE` | ixtiyoriy: «Biz bilan bog'lanish», kirish sahifasi va maxfiylik siyosatidagi qo'ng'iroq raqami (masalan, `+998 90 123 45 67`). Berilmasa — faqat xabar yozish |
   | `ESKIZ_EMAIL`, `ESKIZ_PASSWORD` | SMS orqali kod (pastdagi «SMS orqali tasdiqlash» bo'limi). Berilmasa, kodlar Telegram bot orqali |
   | `ESKIZ_FROM`, `SMS_DAILY_LIMIT` | ixtiyoriy: jo'natuvchi nomi (standart `4546`), sutkalik SMS chegarasi (standart 500) |
   | `SMS_TEMPLATE_REGISTER`, `SMS_TEMPLATE_RESET`, `SMS_TEMPLATE_PHONE` | ixtiyoriy: Eskiz boshqacha matnni tasdiqlasa (pastda) |
   | `CLICK_SERVICE_ID`, `CLICK_MERCHANT_ID`, `CLICK_SECRET_KEY` | Click orqali to'lov (pastdagi «Onlayn to'lovlar» bo'limi) |
   | `PAYME_MERCHANT_ID`, `PAYME_KEY` | Payme orqali to'lov. Sinov paytida `PAYME_TEST=true` va sinov kaliti (Payme foydalanuvchilarga ko'rinmaydi) |
   | `PAYNET_USERNAME`, `PAYNET_PASSWORD`, `PAYNET_SERVICE_ID` | Paynet orqali to'lov (Paynet beradi). Uchalasi ham bo'lmasa Paynet o'chiq |
   | `PAYMENT_MIN`, `PAYMENT_MAX` | ixtiyoriy: bitta to'lov chegarasi, so'm (standart 1 000 va 10 000 000) |
   | `TELEGRAM_CARDS` | ixtiyoriy: `false` — Telegram bildirishnomalari rasmsiz, faqat matn bilan (standart — rasm bilan) |
   | `FCM_SERVICE_ACCOUNT` | Android ilovaga push-bildirishnoma (pastdagi «Push-bildirishnomalar» bo'limi): Firebase xizmat hisobi JSON faylining to'liq matni. Berilmasa — push o'chiq |

   `JWT_SECRET` yaratish (PowerShell):
   ```powershell
   $b = New-Object byte[] 48; [Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($b); [Convert]::ToBase64String($b)
   ```
5. **Deploy** va logni tekshiring:
   - `HikariPool-1 - Added connection org.postgresql…` chiqishi kerak (`jdbc:h2` emas);
   - `Telegram bot @… ishga tushdi` chiqishi kerak;
   - `DIQQAT:` bilan boshlanadigan qatorlar bo'lmasligi kerak.

   Saytdagi o'zgarishlar (HTML, `/js`, `/css`) deploy'dan keyin Android ilovada, Telegram Mini App'da va brauzerda o'zi yangilanadi:
   sahifalar (`*.html`), `/js` va `/css` keshda turadi, lekin har ochilganda serverdan tekshiriladi (`Cache-Control: no-cache` — o'zgarmagan
   bo'lsa qayta yuklanmaydi). AAB'ni qayta yig'ish faqat `mobile/` ichidagi o'zgarishlarda kerak.

### Push-bildirishnomalar (Android ilova, Firebase)

Yangi xabar, taklif, «Olaman», buyurtma holati, ertalabki xulosa — Android ilovada telefonga bildirishnoma bo'lib keladi
(Telegrami ulanmagan haydovchiga yangi mos e'lonlar ham). Sozlanmagan bo'lsa, qolgan hammasi avvalgidek ishlaydi.

1. <https://console.firebase.google.com> → **Add project** (nomi, masalan, `YukGo`; Google Analytics shart emas).
2. Loyihada **Android** belgisi → package name: **`uz.yukgo.app`** → **Register app** → **`google-services.json`** ni yuklab oling
   va `mobile/android/app/` papkasiga qo'ying (u `.gitignore` da — GitHub'ga yuklanmaydi). Qolgan qadamlarni («Add Firebase SDK»)
   o'tkazib yuboring — loyihada tayyor.
3. ⚙️ **Project settings → Service accounts → Generate new private key** → JSON fayl yuklanadi. Faylni Notepad'da oching, butun
   matnini nusxalab, Railway → YukGoo → Variables → **`FCM_SERVICE_ACCOUNT`** ga qo'ying. Bu kalit — maxfiy: faqat Railway'da tursin,
   GitHub'ga, chatga yoki boshqa joyga yubormang. Deploydan keyin logda `Push-bildirishnomalar yoqilgan (Firebase loyiha: …)` chiqadi.
4. Ilovani qayta yig'ing (`mobile/README.md`): `npm install` → `npx cap sync android` → Android Studio'da yangi AAB (versiya 1.1.0).
   `google-services.json` siz yig'ilgan ilova ham ishlaydi — faqat push bo'lmaydi.

Ilova kirgan foydalanuvchidan bildirishnomaga ruxsat so'raydi va qurilma tokenini serverga yuboradi (`POST /api/push/register`,
`push_tokens` jadvali, bir hisobga ko'pi bilan 5 ta telefon); «Chiqish» bosilganda token o'chiriladi. Ilova telefondan o'chirilsa,
Firebase buni aytgan zahoti token ham o'chiriladi.

### SMS orqali tasdiqlash (Eskiz.uz)

Ro'yxatdan o'tish, parolni tiklash va telefon raqamni o'zgartirish kodlari SMS bo'lib keladi.
SMS sozlanmagan bo'lsa, chegaraga yetilsa yoki SMS ketmasa, kod Telegram bot orqali olinadi.

1. <https://my.eskiz.uz> da ro'yxatdan o'ting va balansni to'ldiring. Yangi hisob **test rejimida** bo'ladi: Eskiz
   faqat o'zining sinov matnlarini («Bu Eskiz dan test») yuboradi, tasdiqlash kodlari bormaydi.
2. **«Shartnoma so'rash»** — shartnoma tuzilgach, test rejimi olib tashlanadi.
3. **SMS → «Mening matnlarim»** — quyidagi uchta matnni moderatsiyaga yuboring (123456 o'rnida har safar yangi 6 xonali
   kod bo'ladi). Ilova aynan shu matnlarni yuboradi, harfma-harf mos bo'lishi kerak:
   - `YukGo saytida ro'yxatdan o'tish uchun tasdiqlash kodi: 123456. Kodni hech kimga bermang!`
   - `YukGo saytida parolni tiklash uchun tasdiqlash kodi: 123456. Agar buni siz so'ramagan bo'lsangiz, e'tibor bermang.`
   - `YukGo saytida telefon raqamni o'zgartirish uchun tasdiqlash kodi: 123456. Kodni hech kimga bermang!`

   Eskiz matnni o'zgartirishni so'rasa, tasdiqlangan matnni Railway'ga yozing va kod o'rniga `{code}` qo'ying:
   `SMS_TEMPLATE_REGISTER`, `SMS_TEMPLATE_RESET`, `SMS_TEMPLATE_PHONE` (bittasi hammasi uchun bo'lsa — `SMS_TEMPLATE`).
4. Shartnoma va matnlar tasdiqlangach, Railway'ga `ESKIZ_EMAIL` va `ESKIZ_PASSWORD` (my.eskiz.uz'ga kiradigan email va parol)
   ni yozing. Ungacha yozmang: test rejimida har urinishda «SMS yuborib bo'lmadi» chiqadi. Eskiz alohida jo'natuvchi nomi
   («Nik uchun ariza») bergan bo'lsa, `ESKIZ_FROM` ni ham yozing.
5. O'z raqamingiz bilan sinab ko'ring: ro'yxatdan o'tish yoki parolni tiklashda kod SMS bo'lib kelishi kerak.

SMS pulini begonalar sarflamasligi uchun cheklovlar bor:
- bitta so'rovga ko'pi bilan 3 ta SMS;
- bitta raqamga sutkasiga 5 ta;
- bitta IP'dan soatiga 15 ta, sutkasiga 50 ta;
- hammasi bo'lib sutkasiga `SMS_DAILY_LIMIT` ta.

### Onlayn to'lovlar (Click, Payme, Paynet)

Foydalanuvchi profilida «Balansni to'ldirish» tugmasi chiqadi, agar **daromad tizimi yoqilgan** bo'lsa va kamida bitta
to'lov tizimi ulangan bo'lsa. To'lov tizimlari faqat yuridik shaxs yoki yakka tartibdagi tadbirkor bilan shartnoma tuzadi —
hisob raqamingiz va guvohnomangiz tayyor bo'lsin. Kalitlar **faqat Railway → Variables**'ga yoziladi, GitHub'ga emas.

Kabinetlarga yoziladigan manzillar **Boshqaruv → Daromad → Onlayn to'lovlar** bo'limida ko'rinadi (`PUBLIC_URL` asosida):

| Tizim | Manzil |
|---|---|
| Click — Prepare URL | `https://<domen>/api/pay/click/prepare` |
| Click — Complete URL | `https://<domen>/api/pay/click/complete` |
| Payme — Endpoint URL | `https://<domen>/api/pay/payme` |
| Paynet | `https://<domen>/api/pay/paynet` |

**Click** (<https://merchant.click.uz>)
1. Click bilan shartnoma tuzing — kabinetda xizmat (service) ochiladi.
2. Xizmat sozlamalarida Prepare URL va Complete URL'ni yozing.
3. Railway'ga `CLICK_SERVICE_ID`, `CLICK_MERCHANT_ID`, `CLICK_SECRET_KEY` (kabinetdagi «Secret key») ni yozing va deploy qiling.
4. Click'ning sinov dasturi bilan tekshiring: Prepare/Complete, noto'g'ri imzo (-1), noto'g'ri summa (-2), qayta to'lov (-4) va h.k.
   Sinov uchun to'lov raqami (`merchant_trans_id`): Boshqaruv → Daromad → Onlayn to'lovlar → «Sinov to'lovi».
   Sinov to'lovi to'lansa ham balansga yozilmaydi.

**Payme** (<https://business.paycom.uz>)
1. Kassa oching. «Endpoint URL»ga yuqoridagi manzilni yozing.
2. Kassa «hisob» maydoni: `order_id` (to'lov raqami, bir martalik). Boshqa nom bo'lsa — `PAYME_ACCOUNT_FIELD`.
3. Avval sinov: `PAYME_MERCHANT_ID`, `PAYME_KEY` (sinov kaliti) va `PAYME_TEST=true`. Bu paytda Payme foydalanuvchilarga
   ko'rsatilmaydi va sinov kassasi faqat admin ochgan sinov to'lovlarini qabul qiladi (Payme'ning ochiq sinov kartalari bilan
   haqiqiy balans olinmasligi uchun). Boshqaruv → Daromad → Onlayn to'lovlar → «Sinov to'lovi» bilan Payme to'lovi oching va
   uning raqamini (`order_id`) <https://test.paycom.uz> sinovlarida kiriting. Sinov to'lovi balansga yozilmaydi.
4. Sinovlar o'tgach: haqiqiy kalit, `PAYME_TEST` ni o'chiring.
5. Kabinetda kalit almashtirilsa, Payme `ChangePassword` yuboradi va yangi kalit darhol ishlaydi. Shundan keyin Railway'dagi
   `PAYME_KEY` ni ham yangilang.
6. Fiskal chek kerak bo'lsa: `PAYMENT_MXIK` (MXIK/IKPU kodi), `PAYMENT_PACKAGE_CODE`, `PAYMENT_VAT_PERCENT`.

**Paynet** (<https://paynet.uz>)
1. Paynet bilan «ta'minotchi» (provayder) sifatida shartnoma tuzing. Ular login, parol va xizmat raqamini (serviceId) beradi.
2. Paynet'ga manzilni bering. To'lovchini aniqlash maydoni: `client_id` (YukGo ID; telefon raqami ham qabul qilinadi).
   Boshqa nom kelishilsa — `PAYNET_CLIENT_FIELD`.
3. Railway'ga `PAYNET_USERNAME`, `PAYNET_PASSWORD`, `PAYNET_SERVICE_ID` (bir nechta bo'lsa vergul bilan) yozing.
   Xizmat raqamisiz Paynet yoqilmaydi — boshqa xizmat raqami bilan kelgan so'rov 305 xatosini oladi.
   Paynet ilovasini to'g'ridan-to'g'ri ochadigan havola uchun `PAYNET_MERCHANT_ID` (ixtiyoriy).
4. Paynet o'z test dasturi bilan tekshiradi (GetInformation, PerformTransaction, CheckTransaction, CancelTransaction, GetStatement).

Qo'shimcha:
- To'lov tizimi IP manzillarini bersa, `CLICK_ALLOWED_IPS`, `PAYME_ALLOWED_IPS`, `PAYNET_ALLOWED_IPS` ga yozing
  (vergul bilan, `185.234.113.0/24` ko'rinishida ham bo'ladi). Bo'sh bo'lsa tekshirilmaydi. Railway'da to'g'ri ishlaydi;
  o'z serveringizda faqat Nginx/Caddy ortida (yuqoridagi «Serverga joylash»).
- Hisobini o'chirgan yoki bloklangan foydalanuvchiga yangi to'lov qabul qilinmaydi.
- To'lov tizimi to'langan to'lovni bekor qilsa, summa foydalanuvchi balansidan yechiladi; pul ishlatib bo'lingan bo'lsa,
  bekor qilish rad etiladi va masala to'lov tizimi bilan qo'lda hal qilinadi.
- Har bir to'lov va uning holati: Boshqaruv → Daromad → Onlayn to'lovlar. Balans harakatlari «So'nggi harakatlar»da.

---

## Mobil ilova uchun API

Barcha so'rovlar `Authorization: Bearer <token>` bilan (kirishsiz faqat ro'yxatdan o'tish, kirish, parolni tiklash, hududlar, `/api/config`,
`/api/stats/public` va to'lov tizimlari so'rovlari; qolganlari tokensiz — **401**). Asosiy endpointlar:

| Bo'lim | Endpoint |
|---|---|
| Ro'yxatdan o'tish | `POST /api/auth/register/start` → `POST /api/auth/verify` → `POST /api/auth/register/complete` |
| Kirish | `POST /api/auth/login` `{firstName, lastName, password}` yoki `{phone, password}` |
| Parolni tiklash | `POST /api/auth/reset/start`, `POST /api/auth/reset/complete` |
| Hududlar | `GET /api/locations/regions`, `GET /api/locations/regions/{id}/districts` |
| Profil | `GET/PUT /api/users/me`, `POST /api/users/me/avatar`, `PUT /api/users/me/vehicle`, `PUT /api/users/me/settings`, `GET /api/users/{id}` |
| Shartlar | `POST /api/users/me/terms` (qabul qilish). Qabul qilinmagan bo'lsa kontent yaratuvchi so'rovlar **428** qaytaradi |
| Bloklash | `POST/DELETE /api/users/{id}/block`, `GET /api/users/me/blocks` |
| Buyurtmalar | `GET /api/orders` (e'lonlar: `kind`=CARGO/PARCEL/PASSENGER, `fromRegion`, `fromDistrict`, `toRegion`, `toDistrict`, `bodyType`, `q`, `page`; kirgan foydalanuvchiga — yaqinlik bo'yicha), `POST /api/orders` (`kind`, `seats`), `GET /api/orders/my` (`role`=sender/all, `group`=active/done), `GET /api/orders/{id}` (raqam va aniq manzil — maxfiylik qoidasi bo'yicha) |
| Takliflar | `POST /api/orders/{id}/offers`, `POST .../offers/{offerId}/accept`, `POST /api/orders/{id}/take` |
| Holat | `POST /api/orders/{id}/pickup`, `POST /api/orders/{id}/deliver {code}` (yo'lovchida — kodsiz, `{}`), `POST /api/orders/{id}/cancel` |
| Kuzatuv | `POST /api/orders/{id}/track {lat,lng}`, `GET /api/orders/{id}/track` |
| Yozishma | `GET /api/orders/{id}/messages?after=&driverId=`, `POST /api/orders/{id}/messages {text, driverId}` (e'lon egasi — qaysi haydovchiga; haydovchi — doim o'z yozishmasi), `GET /api/orders/{id}/threads` (e'lon egasiga: yozgan haydovchilar) |
| Baho | `POST /api/orders/{id}/review`, `GET /api/users/{id}/reviews` |
| Reyslar | `GET /api/trips` (`fromRegion`, `fromDistrict`, `toRegion`, `toDistrict`), `POST /api/trips` (`daily`, `seats`; javobda `matches` — mos e'lonlar soni va `plan`), `GET /api/trips/my` (har birida `plan`: mos yo'lovchilar, kishilar, pochtalar, bo'sh o'rinlar), `GET /api/trips/{id}/plan` («Reysga yig'ish»: o'rinlar, mashinadagilar, tavsiya etilgan yo'lovchilar va pochtalar, pochta chegarasi; faqat reys egasiga) |
| Taklif | `GET /api/users/me/referral` |
| Bildirishnomalar | `GET /api/notifications`, `GET /api/notifications/unread-count`, `POST /api/notifications/{id}/read`, `POST /api/notifications/read-all`, `DELETE /api/notifications` (hammasini o'chirish) |
| Biz bilan bog'lanish | `GET/POST /api/support {topic: IDEA/PROBLEM/QUESTION/OTHER, text}`; ega va adminlar: `GET /api/admin/support?from=drivers\|users&status=NEW`, `GET /api/admin/support/counts`, `POST /api/admin/support/{id}/reply {text}`, `POST /api/admin/support/{id}/close` |
| Haydovchi tekshiruvi | `GET/POST /api/users/me/driver-verification` (multipart: `license`, `techPassport`) |
| Balansni to'ldirish | `GET /api/payments/options`, `POST /api/payments/topup {provider, amount}`, `GET /api/payments/{id}` |
| To'lov tizimlari | `POST /api/pay/click/prepare`, `/api/pay/click/complete`, `/api/pay/payme`, `/api/pay/paynet` (token emas — imzo yoki login-parol) |
| Admin | `/api/admin/**` (stats, users, orders, reports, broadcast, logs, channels, sources, driver-verifications, payments, support, rules — `GET/PUT /api/admin/rules {parcelCap}`) |

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
  trip/          haydovchi reyslari, mos e'lonlarni topish (MatchService) va «Reysga yig'ish» (TripPlanner)
  review/        baholash va reyting
  admin/         admin/ega paneli, shikoyatlar, jurnal
  notification/  sayt ichidagi, push va Telegram bildirishnomalari (Msg — matn, CardRenderer — rasm-kartochka)
  location/      viloyat va tumanlar
  security/      JWT, Spring Security
src/main/resources/static/   veb-sayt (SPA): index.html, css/, js/; promo.html — chop etiladigan varaqa va stiker
src/main/resources/fonts/    Telegram kartochkalari shriftlari (Onest, Unbounded — SIL Open Font License, OFL.txt)
marketing/                   jalb qilish rejasi, kanal postlari, hamkor va agentlar uchun materiallar
```
