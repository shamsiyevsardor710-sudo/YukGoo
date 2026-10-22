# YukGo: foydalanuvchi jalb qilish to'plami

Bu papkada odamlarni Telegram guruh va kanallardan YukGo'ga olib kelish uchun hamma narsa bor. Asosiy fikr: odamlarni Telegramdan **olib chiqmaymiz**. YukGo Telegram ichida eng qulay variant bo'ladi: e'lonlar kanallarda chiqaveradi, lekin bog'lanish, narx kelishish va kuzatish YukGo orqali bo'ladi.

| Fayl | Nima uchun |
|---|---|
| `kanal-postlari.md` | YukGo kanali va hamkor kanallar uchun tayyor postlar |
| `hamkor-kanallarga-taklif.md` | Kanal egalariga yoziladigan xabarlar, shartlar va to'lov qoidasi |
| `dala-xodimlari.md` | Bozor, yoqilg'i shoxobchasi va to'xtash joylarida ishlaydigan agentlar uchun yo'riqnoma |
| `chop-etish/` | Chop etishga tayyor A4 PDF'lar: haydovchilar va yuk egalari uchun varaqa (bir A4 dan 2 ta), stiker (bir A4 dan 6 ta) |

`chop-etish/` dagi QR kodlar quyidagi manba kodlariga ishlaydi: `varaqa_haydovchi`, `varaqa_yukchi`, `stiker`. Ularni **Boshqaruv → Manbalar** da shu kodlar bilan yarating, shunda nomlari chiroyli ko'rinadi. Yaratmasangiz ham hisob yuritiladi, faqat kod nomi bilan ko'rinadi. Har bir agent yoki hamkor uchun alohida varaqa kerak bo'lsa, Manbalar'da uning kodini yarating va **«Varaqa»** tugmasini bosing. Sahifa o'sha kod bilan QR chiqaradi, «Chop etish / PDF» tugmasi bilan saqlaysiz.

---

## 1. Tizimda nima tayyor

| Imkoniyat | Qayerda | Natija |
|---|---|---|
| **Kanallarga avtomatik e'lon** | Boshqaruv → Kanallar | Har yangi ochiq buyurtma ulangan kanallarga chiqadi: yuk, yo'nalish, sana, narx. Yuk egasining ismi va telefoni chiqmaydi. «📩 Taklif berish» tugmasi buyurtmani YukGo'da ochadi. Haydovchi topilsa, post «✅ Haydovchi topildi» bo'lib o'zgaradi |
| **Botga oddiy yozib e'lon berish** | @YukGoobot | Odam guruhga qanday yozsa, botga ham shunday yozadi: *Toshkent - Samarqand, 5 tonna mebel, ertaga, 3 mln*. Bot yo'nalish, og'irlik, sana, narx va kuzovni tushunadi va to'ldirilgan formani ochadi |
| **Manba havolalari** | Boshqaruv → Manbalar | Har kanal, hamkor, varaqa va agent uchun alohida kod. Kim nechta odam olib kelgani, ulardan nechtasi haydovchi, nechtasi yuk egasi va nechta buyurtma yakunlangani ko'rinadi |
| **Taklif havolasi** | Har foydalanuvchining Profil sahifasida | Do'stini taklif qiladi. Do'sti birinchi buyurtmasini yakunlasa, ikkalasiga bonus tushadi |
| **Bonuslar** | Boshqaruv → Daromad → «Yangi foydalanuvchilarni jalb qilish» | Taklif bonusi, yangi haydovchiga birinchi N ta buyurtma komissiyasiz, birinchi «Shoshilinch» e'lon bepul |
| **«Tekshirilgan haydovchi»** | Profil (haydovchi), Boshqaruv → Tekshiruv | Haydovchi guvohnoma va texpasport rasmini yuboradi, admin tekshiradi. Belgi profilda, takliflarda va ro'yxatda ko'rinadi, rasmlar qarordan keyin o'chiriladi |

## 2. Birinchi 3 kun: sozlash

1. **O'z kanalingizni oching** (masalan, «YukGo — yuklar»). @YukGoobot'ni admin qiling va «Xabar joylash» huquqini bering. Keyin Boshqaruv → Kanallar → «Qo'shish». Viloyatlar bo'yicha alohida kanal ochmoqchi bo'lsangiz, har biriga viloyat filtrini qo'ying.
2. **«Taklif berish» tugmasi.** @BotFather'da Main Mini App yoqilsa, tugma ilovani bir bosishda ochadi. Yo'riqnoma Kanallar sahifasida bor. Yoqmasangiz ham ishlaydi, faqat avval bot ochiladi.
3. **Manbalar**'da kodlarni yarating: `varaqa_haydovchi`, `varaqa_yukchi`, `stiker`, keyin har agent va hamkor uchun bittadan (`agent_ali`, `kanal_yukmarkazi`).
4. **Bonuslarni sozlang** (Daromad bo'limi, faqat ega o'zgartira oladi). Boshlash uchun taklif:
   - yangi haydovchiga birinchi **3 ta** buyurtma komissiyasiz;
   - birinchi «Shoshilinch» e'lon bepul;
   - taklif bonusi faqat daromad tizimi yoqilganda beriladi: bonus balansga tushadi va komissiyaga ishlatiladi. Bonus faqat taklif qilingan odamning birinchi yakunlangan buyurtmasi uchun, taklif qilgan odamning o'zi bilan qilingan buyurtma hisobga olinmaydi.
5. **1–2 yo'nalishni tanlang** (masalan, o'zingiz yaxshi biladigan Toshkent — Surxondaryo) va o'sha yo'nalishda har kuni yangi yuk bo'lishiga erishing. Haydovchi yuk bor joyga keladi.

## 3. 30 kunlik reja

| Hafta | Nima qilinadi | Natijani qayerdan ko'rasiz |
|---|---|---|
| **1** | O'z kanalingiz, 1–2 yo'nalish. Guruhlarda e'lon berayotgan yuk egalariga qo'ng'iroq qilib, e'lonini YukGo'ga bepul joylab berishni taklif qiling (faqat ularning roziligi bilan). Doimiy yuk chiqaradiganlarni (ulgurji bozor, qurilish mollari, mebel do'konlari, sexlar) bittalab ro'yxatdan o'tkazing | Bozor sahifasida har kuni yangi e'lon bormi |
| **2** | 3–5 ta hamkor kanal (`hamkor-kanallarga-taklif.md`). 2–4 ta dala agenti (`dala-xodimlari.md`). Haydovchilarga stiker | Manbalar: kod bo'yicha kelganlar |
| **3** | Manbalar'dagi raqamlarga qarang. «Faol» ustuni yaxshi bo'lgan kanal va agentga ko'proq, natija bermaganiga kamroq pul bering. Ikkinchi yo'nalishni oching | Manbalar: sarflangan pul ÷ «Faol» |
| **4** | Haqiqiy natijalar bilan postlar (foydalanuvchi roziligi bilan). Tekshirilgan haydovchilar sonini oshiring. Komissiya haqida oldindan e'lon qiling, lekin hali yoqmang | Statistika, Tekshiruv |

## 4. Budjet va to'lov qoidalari

Pulni **natija uchun** to'lang, ro'yxatdan o'tganlar soni uchun emas. Bitta odam o'n ta soxta hisob ochishi oson, yakunlangan buyurtmani soxtalashtirish esa qiyin.

| Kanal | Mo'ljal | Izoh |
|---|---|---|
| Hamkor kanalda bir martalik post | 100 minggacha obunachisi bor mavzuli kanalda 300–500 ming so'm (2025-yil boshi) | Telegram'ning Suggested Posts orqali to'lansa, post 24 soatdan oldin o'chirilsa pul qaytadi |
| Hamkor kanal, natija uchun | Sizning taklifingiz: kanal kodi bilan kelgan har bir **faol** foydalanuvchi uchun belgilangan summa, oyiga bir marta | Raqam: Manbalar → kanal qatori → «Faol» |
| Dala agenti | Sizning taklifingiz: agent kodi bilan kelgan har bir **faol** foydalanuvchi uchun (`dala-xodimlari.md`) | Raqam: Manbalar → agent qatori → «Faol». Kunlik yo'l haqi alohida |
| Stiker yopishtirgan haydovchi | Sizning taklifingiz: oyiga kichik summa, stiker turgan bo'lsa (oy oxirida rasm yuborsin) | Yo'lda yuradigan reklama |
| Taklif bonusi | Daromad sozlamalarida (0 = o'chiq) | Faqat do'sti birinchi buyurtmasini yakunlaganda, taklif qilgan odam bilan qilingan buyurtma hisobga olinmaydi |
| Telegram Ads | 1000 ko'rishga 0,1 TON dan | Yuqoridagilar ishlagandan keyin sinab ko'ring |

**«Faol»** — Manbalar'dagi ustun: shu kod bilan kelganlardan kamida bitta buyurtmasi yakunlanganlar (haydovchi sifatida yetkazgan yoki yuk egasi sifatida yuki yetkazilgan).

**Hisob-kitob:** bitta faol foydalanuvchining narxi = shu manbaga sarflangan pul ÷ «Faol». Har oy oxirida jadvalga yozing va eng arzonini kuchaytiring.

## 5. Har hafta nimani tekshirish kerak

- **Manbalar (7 kun):** qaysi koddan nechta odam keldi, ulardan nechtasi haydovchi, nechtasi yuk egasi, nechtasi faol.
- **Bozor:** ochiq e'lonlar soni. 0 bo'lsa haydovchilar ilovaga qaytmaydi, eng muhim raqam shu.
- **Statistika:** bugungi buyurtmalar, yo'ldagi, yetkazilgan.
- **Tekshiruv:** kutayotgan arizalar. 24 soat ichida ko'rib chiqing, kechiksa haydovchi ishonchini yo'qotadi.
- **Kanallar:** har kanalning e'lonlar soni va undan kelganlar. Natija bermayotgan kanalni «To'xtatish» mumkin.

## 6. Ehtiyot bo'ling

- **Komissiyani shoshib yoqmang.** TIRGO'da pullik xizmat yoqilgach faollik 70% dan ko'proq tushib ketgan. Oldindan e'lon qiling («keyinchalik kichik komissiya bo'ladi»), yangi haydovchilarga birinchi buyurtmalarni komissiyasiz qoldiring. Daromadni avval ixtiyoriy pullik xizmatdan oling: «Shoshilinch» e'lon.
- **Boshqa guruhlardagi e'lon va raqamlarni egasining roziligisiz ko'chirmang.** Bu ham qonuniy xavf, ham obro'ga zarar. Kanallarga faqat YukGo'da e'lon bergan odamlarning yuki chiqadi, ismi va telefoni esa chiqmaydi.
- **Hamkor kanalni egasining roziligisiz ulamang.** Bot u yerga admin bo'lishi uchun egasi uni o'zi qo'shishi kerak.
- **Foydalanuvchi hikoyasini** (sharh, rasm, ism) faqat uning roziligi bilan e'lon qiling.
- **Raqobatchilar bor:** Diip.uz (2026-yil mayda Yoshlar Ventures'dan $100 ming sarmoya olgan) va Transport vazirligining E-Logistika platformasi. Bitta yo'nalishda ulardan aniq ustun bo'ling, masalan, viloyatlararo va shahar ichidagi kichik yuklar uchun eng tez, o'zbekcha va Telegram ichida ishlaydigan xizmat.

## Manbalar

- [spot.uz — Telegram kanallarida reklama narxlari (2025-yil yanvar)](https://www.spot.uz/oz/2025/01/23/prices/)
- [Telegram — Suggested posts (2025-yil iyul)](https://telegram.org/blog/checklists-suggested-posts)
- [Digital Business — TIRGO tajribasi (2026-yil sentabr)](https://digitalbusiness.kz/uz/2026-09-17/ozbekistonlik-tadbirkor-xalqaro-yuk-tashishni-raqamlashtirib-oyiga-80-ming-daromadga-chiqdi/)
- [Dealroom — Diip.uz](https://app.dealroom.co/companies/diip_uz)
- [Transport vazirligi — E-Logistika platformasi](https://navoiy.mintrans.uz/en/news/yangilik19)
- [InviteMember — Telegram Ads 2026](https://blog.invitemember.com/telegram-ads-in-2026-setup-costs-and-requirements/)
