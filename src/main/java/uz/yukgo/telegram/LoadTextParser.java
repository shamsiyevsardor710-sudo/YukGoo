package uz.yukgo.telegram;

import uz.yukgo.order.CargoType;
import uz.yukgo.order.OrderKind;
import uz.yukgo.user.BodyType;

import java.time.LocalDate;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Telegram guruhlaridagidek oddiy yozilgan yuk e'lonini tushunadi:
 * "Toshkent - Samarqand, 5 tonna mebel, tent kerak, ertaga, 3 mln" → yo'nalish, og'irlik, kuzov, sana, narx, yuk turi.
 * Lotin va kirill yozuvi, ruscha shahar nomlari ham tushuniladi. Telefon raqamlar e'tiborga olinmaydi
 * (narx yoki og'irlik deb o'qilmasin) va formaga o'tkazilmaydi — e'londa foydalanuvchining o'z raqami ishlatiladi.
 */
public final class LoadTextParser {

    /** Viloyat yoki tuman: districtId null bo'lsa — butun viloyat */
    public record Place(long regionId, Long districtId, String name) {}

    /** Bitta viloyat: nomi va tumanlari (bazadan) */
    public record RegionInfo(long id, String name, Map<Long, String> districts) {}

    /**
     * kind — yuk, pochta yoki yo'lovchi ("2 kishi", "yo'lovchi", "pochta"); seats — yo'lovchilar soni.
     * driverOffer — bu haydovchining e'loni ("Kosonsoy - Toshkent, pochta va yo'lovchi olaman", "2 ta bo'sh joy bor"):
     * bot unga yuk formasi emas, reys e'lon qilishni taklif qiladi.
     */
    public record Parsed(Place from, Place to, Double weightKg, Long price, boolean negotiable, LocalDate pickupDate,
                         BodyType bodyType, CargoType cargoType, String title, String description,
                         OrderKind kind, Integer seats, boolean driverOffer) {
        /** Yo'nalish topilgandagina e'lon deb hisoblanadi */
        public boolean isLoad() {
            return from != null && to != null;
        }
    }

    private record Alias(String[] words, Place place) {}

    /** Matnda topilgan joy: rol (0 — noma'lum, 1 — qayerdan, 2 — qayerga) va bo'lak (ajratgichlar orasidagi qism) */
    private record Hit(Place place, int role, int segment) {}

    /**
     * Yo'nalish ajratgichlari: "Toshkent - Samarqand", "Andijon → Toshkent", "Namangan-Andijon", yangi qator.
     * Ajratgich bilan bo'lingan bir bo'lakdagi joylar bitta manzil: "Andijon Asaka → Toshkent Sergeli".
     */
    private static final Pattern SEPARATOR = Pattern.compile(
            "\\s*(?:-+>|=+>|[\u2192\u27F6\u27A1\u279D\u21D2\u21E8\u00BB>|\u2014\u2013]+)\\s*|\\s+-+\\s+|(?<=\\p{L})-+(?=\\p{L})|\\R");
    private static final String SEP = "qqsepqq";
    /** Oddiy so'z bilan bir xil shahar nomlari — qisqa nomi qo'shilmaydi ("shirin" — "Shirin shahri") */
    private static final Set<String> NO_SHORT_NAME = Set.of("shirin");

    private static final Pattern PHONE = Pattern.compile("(\\+?998[\\s\\-()]*\\d{2}[\\s\\-()]*\\d{3}[\\s\\-]*\\d{2}[\\s\\-]*\\d{2})"
            + "|(\\b\\d{2}[\\s\\-]?\\d{3}[\\s\\-]?\\d{2}[\\s\\-]?\\d{2}\\b)");
    private static final Pattern TONS = Pattern.compile("(\\d+(?:[.,]\\d+)?)\\s*(?:t|tn|tonna|tonn|tonnali)\\b");
    private static final Pattern KILOS = Pattern.compile("(\\d+(?:[.,]\\d+)?)\\s*(?:kg|kilo|kilogramm?)\\b");
    private static final Pattern MILLION = Pattern.compile("(\\d+(?:[.,]\\d+)?)\\s*(?:mln|million|millon|mil)\\b");
    private static final Pattern THOUSAND = Pattern.compile("(\\d+(?:[.,]\\d+)?)\\s*(?:ming|k|tys)\\b");
    private static final Pattern PLAIN_PRICE = Pattern.compile("(?<![\\d.,])(\\d{1,3}(?:[ .,]\\d{3}){1,3}|\\d{5,10})(?![\\d.,])\\s*(?:som|sum|so'm|sum\\.)?");
    private static final Pattern DAY_MONTH_NAME = Pattern.compile("(\\d{1,2})\\s*-?\\s*(yanvar|fevral|mart|aprel|may|iyun|iyul|avgust|sentabr|sentyabr|oktabr|oktyabr|noyabr|dekabr)");
    private static final Pattern DAY_MONTH_NUM = Pattern.compile("(?<!\\d|\\d[.,])(\\d{1,2})[./](\\d{1,2})(?:[./](\\d{2,4}))?(?!\\d|[.,]\\d|\\s*(?:t|tn|tonna|kg|mln|ming)\\b)");
    private static final String[] MONTHS = {"yanvar", "fevral", "mart", "aprel", "may", "iyun", "iyul", "avgust", "sent", "okt", "noyabr", "dekabr"};

    private static final Map<Character, String> CYR = new HashMap<>();

    static {
        String[][] t = {
                {"а", "a"}, {"б", "b"}, {"в", "v"}, {"г", "g"}, {"ғ", "g"}, {"д", "d"}, {"е", "e"}, {"ё", "yo"}, {"ж", "j"},
                {"з", "z"}, {"и", "i"}, {"й", "y"}, {"к", "k"}, {"қ", "q"}, {"л", "l"}, {"м", "m"}, {"н", "n"}, {"о", "o"},
                {"п", "p"}, {"р", "r"}, {"с", "s"}, {"т", "t"}, {"у", "u"}, {"ў", "o"}, {"ф", "f"}, {"х", "x"}, {"ҳ", "h"},
                {"ц", "ts"}, {"ч", "ch"}, {"ш", "sh"}, {"щ", "sh"}, {"ъ", ""}, {"ы", "i"}, {"ь", ""}, {"э", "e"}, {"ю", "yu"},
                {"я", "ya"},
        };
        for (String[] p : t) CYR.put(p[0].charAt(0), p[1]);
    }

    /**
     * Viloyat va shaharlarning keng tarqalgan boshqa yozilishlari (ruscha, inglizcha, xatoli).
     * "muqobil" — butun viloyatga, "muqobil=Tuman nomi" — aniq shahar yoki tumanga (bazadagi nom bilan).
     * Bazadagi "X shahri" tumanlari uchun qisqa "X" nomi avtomatik qo'shiladi (Termiz, Urganch, Qo'qon...).
     */
    private static final Map<String, String[]> REGION_ALIASES = new LinkedHashMap<>();

    static {
        REGION_ALIASES.put("Toshkent shahri", new String[]{"toshkent", "tashkent", "toshkent shahar", "tashkent city"});
        REGION_ALIASES.put("Toshkent viloyati", new String[]{"toshkent vil", "tashkentskaya oblast", "chirchik=Chirchiq shahri",
                "almalik=Olmaliq shahri", "almalyk=Olmaliq shahri", "bekabad=Bekobod shahri", "yangiyul=Yangiyo'l shahri",
                "ahangaran=Ohangaron shahri", "kibray=Qibray", "gazalkent=Bo'stonliq"});
        REGION_ALIASES.put("Andijon viloyati", new String[]{"andijan=Andijon shahri", "andizhan=Andijon shahri", "khanabad=Xonobod shahri"});
        REGION_ALIASES.put("Buxoro viloyati", new String[]{"bukhara=Buxoro shahri", "buxara=Buxoro shahri", "buhara=Buxoro shahri",
                "buhoro=Buxoro shahri", "gijduvan=G'ijduvon", "kagan=Kogon shahri", "karakul=Qorako'l"});
        REGION_ALIASES.put("Farg'ona viloyati", new String[]{"fergana=Farg'ona shahri", "ferghana=Farg'ona shahri", "kokand=Qo'qon shahri",
                "qoqand=Qo'qon shahri", "margilan=Marg'ilon shahri", "kuvasay=Quvasoy shahri"});
        REGION_ALIASES.put("Jizzax viloyati", new String[]{"djizak=Jizzax shahri", "jizzakh=Jizzax shahri", "jizak=Jizzax shahri",
                "dzhizak=Jizzax shahri", "jizzah=Jizzax shahri", "jizax=Jizzax shahri"});
        REGION_ALIASES.put("Xorazm viloyati", new String[]{"xorazm", "horazm", "khorezm", "xorezm", "urgench=Urganch shahri",
                "khiva=Xiva shahri", "hiva=Xiva shahri"});
        REGION_ALIASES.put("Namangan viloyati", new String[]{"kasansay=Kosonsoy"});
        REGION_ALIASES.put("Navoiy viloyati", new String[]{"navoi=Navoiy shahri", "zarafshan=Zarafshon shahri", "uchkuduk=Uchquduq"});
        REGION_ALIASES.put("Qashqadaryo viloyati", new String[]{"qashqadaryo", "kashkadarya", "karshi=Qarshi shahri",
                "shakhrisabz=Shahrisabz shahri", "guzar=G'uzor", "mubarek=Muborak"});
        REGION_ALIASES.put("Qoraqalpog'iston Respublikasi", new String[]{"qoraqalpogiston", "karakalpakstan", "qoraqalpoq",
                "khodjeyli=Xo'jayli", "muynak=Mo'ynoq", "turtkul=To'rtko'l"});
        REGION_ALIASES.put("Samarqand viloyati", new String[]{"samarkand=Samarqand shahri", "samarkant=Samarqand shahri",
                "kattakurgan=Kattaqo'rg'on shahri"});
        REGION_ALIASES.put("Sirdaryo viloyati", new String[]{"sirdaryo", "syrdarya", "sirdarya", "gulistan=Guliston shahri"});
        REGION_ALIASES.put("Surxondaryo viloyati", new String[]{"surxondaryo", "surkhandarya", "surxon", "surhondaryo",
                "termez=Termiz shahri", "denau=Denov", "sherabad=Sherobod", "shurchi=Sho'rchi", "baysun=Boysun", "kumkurgan=Qumqo'rg'on"});
    }

    /** Mashina turi so'zlari — faqat hozirgi 6 tur (tentli, refrijerator, samosval... — yuk mashinasi) */
    private static final Object[][] BODY_WORDS = {
            {"refrijerator", BodyType.TRUCK}, {"ref", BodyType.TRUCK}, {"muzlatkichli", BodyType.TRUCK},
            {"izoterm", BodyType.TRUCK}, {"izotermik", BodyType.TRUCK},
            {"tentli", BodyType.TRUCK}, {"tent", BodyType.TRUCK}, {"fura", BodyType.TRUCK},
            {"samosval", BodyType.TRUCK}, {"tral", BodyType.TRUCK}, {"sisterna", BodyType.TRUCK},
            {"kamaz", BodyType.TRUCK}, {"isuzu", BodyType.TRUCK}, {"gruzovik", BodyType.TRUCK},
            {"bortli", BodyType.BORT}, {"bort", BodyType.BORT},
            {"konteyner", BodyType.CONTAINER}, {"kontejner", BodyType.CONTAINER},
            {"labo", BodyType.FURGON}, {"damas", BodyType.FURGON},
            {"gazel", BodyType.FURGON}, {"porter", BodyType.FURGON}, {"furgon", BodyType.FURGON},
    };

    /**
     * Yo'lovchi: "yo'lovchi", "passajir", "poputchik" yoki "2 kishi", "3 ta odam" (lekin "20 kishilik qozon" — yuk).
     * Matnda yuk belgisi bo'lsa (og'irlik, "mebel", "sement", yuk mashinasi, "gruz") kishilar soni hisoblanmaydi:
     * "5 tonna mebel, yuklashga 2 ta odam kerak", "gruz 3 mesta, 200 kg" — bu yuk.
     */
    private static final String[] PASSENGER_WORDS = {"yolovchi", "passajir", "pasajir", "passazhir", "poputch"};
    private static final Pattern SEATS = Pattern.compile(
            "(?<![\\d.,])(\\d{1,2})\\s*(?:ta\\s+)?(?:kishi|odam|nafar|chelovek|yolovchi|passajir|pasajir|mesto|mesta)(?!lik)");
    /** Haydovchi e'lonidagi bo'sh o'rinlar: "3 ta bo'sh joy", "2 joy bor" */
    private static final Pattern OFFER_SEATS = Pattern.compile("(?<![\\d.,])(\\d{1,2})\\s*(?:ta\\s+)?(?:bosh\\s+)?(?:joy|orin|mesta|mesto)");
    /** Pochta: hujjat, konvert, posilka */
    private static final String[] PARCEL_WORDS = {"pochta", "posilka", "posylka", "konvert", "hujjat"};
    /**
     * Haydovchining e'loni: "olaman", "olib ketaman", "2 ta bo'sh joy bor", "3 ta joy bor, yo'lovchi kerak".
     * Matn boshi va oxirida probel bor, shuning uchun " joy bor " "joy bormi" ga mos kelmaydi.
     */
    private static final String[] OFFER_WORDS = {" olaman ", " olib ketaman ", " olib ketamiz ", " olib boraman ", " joy bor ",
            " joylar bor ", " joyim bor ", " mesto est ", " mesta est ", " vozmu ", " voz mu "};
    /** So'rov belgisi — bunday matn haydovchining e'loni emas: "mashina kerak", "joy kerak", "nujna mashina" */
    private static final Pattern REQUEST = Pattern.compile(
            " (?:mashina|moshina|avto|taksi|taxi|haydovchi|shofyor|shofer|transport|joy|orin|gazel|labo|damas|isuzu|kamaz|fura|furgon)[a-z]* kerak "
                    + "| nujen | nujna | nujno | nujny ");
    /** Yo'lovchi deb hisoblanmaydigan og'irlik (kg): undan og'ir — yuk */
    private static final double HEAVY_KG = 50;

    private static final Object[][] CARGO_WORDS = {
            {"mebel", CargoType.FURNITURE}, {"divan", CargoType.FURNITURE}, {"shkaf", CargoType.FURNITURE},
            {"kochish", CargoType.HOUSEHOLD}, {"kochirish", CargoType.HOUSEHOLD}, {"pereezd", CargoType.HOUSEHOLD},
            {"sement", CargoType.BUILDING_MATERIALS}, {"gisht", CargoType.BUILDING_MATERIALS}, {"blok", CargoType.BUILDING_MATERIALS},
            {"qum", CargoType.BUILDING_MATERIALS}, {"shagal", CargoType.BUILDING_MATERIALS}, {"armatura", CargoType.BUILDING_MATERIALS},
            {"kafel", CargoType.BUILDING_MATERIALS}, {"qurilish", CargoType.BUILDING_MATERIALS}, {"taxta", CargoType.BUILDING_MATERIALS},
            {"kartoshka", CargoType.PRODUCE}, {"piyoz", CargoType.PRODUCE}, {"sabzi", CargoType.PRODUCE}, {"olma", CargoType.PRODUCE},
            {"uzum", CargoType.PRODUCE}, {"meva", CargoType.PRODUCE}, {"sabzavot", CargoType.PRODUCE}, {"qovun", CargoType.PRODUCE},
            {"tarvuz", CargoType.PRODUCE}, {"pomidor", CargoType.PRODUCE}, {"bodring", CargoType.PRODUCE},
            {"un", CargoType.FOOD}, {"shakar", CargoType.FOOD}, {"guruch", CargoType.FOOD}, {"oziq", CargoType.FOOD}, {"yog", CargoType.FOOD},
            {"xolodilnik", CargoType.APPLIANCES}, {"muzlatkich", CargoType.APPLIANCES}, {"televizor", CargoType.APPLIANCES},
            {"texnika", CargoType.APPLIANCES}, {"posilka", CargoType.PARCEL}, {"karobka", CargoType.PARCEL}, {"hujjat", CargoType.DOCUMENTS},
            {"chorva", CargoType.LIVESTOCK}, {"qoy", CargoType.LIVESTOCK}, {"sigir", CargoType.LIVESTOCK},
            {"stanok", CargoType.EQUIPMENT}, {"uskuna", CargoType.EQUIPMENT}, {"shisha", CargoType.FRAGILE}, {"oyna", CargoType.FRAGILE},
    };

    private final List<Alias> aliases = new ArrayList<>();
    /** "X shahri" tumanlari — "Andijon Asaka", "Qarshi Kasbi" kabi yozuvda shahar viloyatni bildiradi */
    private final Set<Long> cities = new HashSet<>();

    public LoadTextParser(Collection<RegionInfo> regions) {
        // Tartib muhim: bir xil uzunlikdagi nomlardan birinchi qo'shilgani ishlatiladi
        // 1) viloyatlarning rasmiy nomi; 2) tumanlar va "X shahri" ning qisqa nomi; 3) boshqa yozilishlar
        for (RegionInfo r : regions) add(r.name(), new Place(r.id(), null, r.name()));
        for (RegionInfo r : regions) {
            for (Map.Entry<Long, String> d : r.districts().entrySet()) {
                Place dp = new Place(r.id(), d.getKey(), r.name() + ", " + d.getValue());
                add(d.getValue(), dp);
                if (d.getValue().endsWith(" shahri")) {
                    cities.add(d.getKey());
                    String shortName = d.getValue().substring(0, d.getValue().length() - " shahri".length());
                    if (!NO_SHORT_NAME.contains(norm(shortName).trim())) add(shortName, dp);
                }
            }
        }
        for (RegionInfo r : regions) {
            String[] extra = REGION_ALIASES.get(r.name());
            if (extra == null) continue;
            for (String a : extra) {
                int eq = a.indexOf('=');
                if (eq < 0) {
                    add(a, new Place(r.id(), null, r.name()));
                    continue;
                }
                String target = a.substring(eq + 1);
                r.districts().entrySet().stream().filter(e -> e.getValue().equals(target)).findFirst()
                        .ifPresent(e -> add(a.substring(0, eq), new Place(r.id(), e.getKey(), r.name() + ", " + e.getValue())));
            }
        }
        // Ko'p so'zli nom birinchi: "toshkent viloyati" — "toshkent" dan oldin (tartiblash barqaror)
        aliases.sort((a, b) -> Integer.compare(b.words().length, a.words().length));
    }

    private void add(String name, Place place) {
        String n = norm(name).trim();
        if (n.length() < 3) return;
        aliases.add(new Alias(n.split("\\s+"), place));
    }

    /** Kichik harf, kirill → lotin, tutuq belgilarisiz, harf-raqamdan boshqasi probel */
    public static String norm(String s) {
        StringBuilder sb = new StringBuilder(s.length() + 8);
        for (char c : s.toLowerCase(Locale.ROOT).toCharArray()) {
            String t = CYR.get(c);
            if (t != null) sb.append(t);
            else if (c == '\'' || c == '‘' || c == '’' || c == 'ʻ' || c == 'ʼ' || c == '`' || c == '´') continue;
            else sb.append(c);
        }
        return sb.toString().replaceAll("[^a-z0-9.,+\\-/ ]+", " ").replaceAll("\\s+", " ");
    }

    public Parsed parse(String original, LocalDate today) {
        if (original == null || original.isBlank()) {
            return new Parsed(null, null, null, null, false, null, null, CargoType.GENERAL, null, null, OrderKind.CARGO, null, false);
        }
        String cleanOriginal = PHONE.matcher(original).replaceAll(" ").replaceAll("[ \\t]+", " ").trim();
        String text = " " + norm(PHONE.matcher(original).replaceAll(" ")) + " ";

        // ---- Yo'nalish ----
        // Joylar matndagi tartibda yig'iladi; "-dan" — qayerdan, "-ga" — qayerga (ruscha "iz X v Y" ham).
        // Aniq ko'rsatilmasa: birinchi joy — qayerdan, keyingi boshqa joy — qayerga.
        String routeText = norm(SEPARATOR.matcher(PHONE.matcher(original).replaceAll(" ")).replaceAll(" " + SEP + " "));
        String[] words = routeText.replaceAll("[^a-z0-9 ]", " ").trim().split("\\s+");
        boolean[] used = new boolean[words.length];
        List<Hit> hits = new ArrayList<>();
        int segment = 0;
        for (int i = 0; i < words.length; i++) {
            if (words[i].equals(SEP)) {
                used[i] = true;
                if (!hits.isEmpty() && hits.get(hits.size() - 1).segment() == segment) segment++;
                continue;
            }
            for (Alias a : aliases) {
                int role = matchAt(words, i, a.words());
                if (role < 0) continue;
                if (role == 0 && i > 0) {
                    String prev = words[i - 1];
                    if (prev.equals("iz") || prev.equals("ot")) role = 1;
                    else if (prev.equals("v") || prev.equals("vo") || prev.equals("do")) role = 2;
                }
                hits.add(new Hit(a.place(), role, segment));
                for (int k = 0; k < a.words().length; k++) used[i + k] = true;
                i += a.words().length - 1;
                break;
            }
        }
        // Bir bo'lakdagi "viloyat + tuman" ("Toshkent, Chilonzor") — bitta joy. "Shahar + tuman" ("Andijon Asaka") ham —
        // agar ajratgich bor yoki joylar ko'p bo'lsa; aks holda "Termiz Denov" — Termizdan Denovga.
        boolean cityIsRegion = hits.size() >= 3 || hits.stream().map(Hit::segment).distinct().count() > 1;
        List<Hit> found = new ArrayList<>();
        for (Hit h : hits) {
            int last = found.size() - 1;
            Hit prev = last < 0 ? null : found.get(last);
            boolean rolesFit = prev != null && (prev.role() == 0 || h.role() == 0 || prev.role() == h.role());
            if (rolesFit && prev.segment() == h.segment()
                    && (refines(prev.place(), h.place()) || (cityIsRegion && narrows(prev.place(), h.place())))) {
                found.set(last, new Hit(h.place(), prev.role() != 0 ? prev.role() : h.role(), h.segment()));
            } else if (prev == null || !identical(prev.place(), h.place())) {
                found.add(h);
            }
        }
        Place from = null, to = null;
        for (Hit h : found) {
            if (h.role() == 1 && from == null) from = h.place();
            if (h.role() == 2 && to == null) to = h.place();
        }
        for (Hit h : found) {
            Place p = h.place();
            if (p == from || p == to) continue;
            if (from == null && !same(p, to)) from = p;
            else if (to == null && !same(p, from)) to = p;
        }

        // ---- Og'irlik ----
        Double weight = null;
        String rest = text;
        Matcher m = TONS.matcher(rest);
        if (m.find()) { weight = num(m.group(1)) * 1000; rest = cut(rest, m); }
        else if ((m = KILOS.matcher(rest)).find()) { weight = num(m.group(1)); rest = cut(rest, m); }
        if (weight != null && (weight <= 0 || weight > 100_000)) weight = null;

        // ---- Narx ----
        Long price = null;
        if ((m = MILLION.matcher(rest)).find()) { price = Math.round(num(m.group(1)) * 1_000_000); rest = cut(rest, m); }
        else if ((m = THOUSAND.matcher(rest)).find()) { price = Math.round(num(m.group(1)) * 1_000); rest = cut(rest, m); }
        else {
            m = PLAIN_PRICE.matcher(rest);
            while (m.find()) {
                long v = Long.parseLong(m.group(1).replaceAll("[ .,]", ""));
                if (v >= 10_000 && v <= 500_000_000) { price = v; rest = cut(rest, m); break; }
            }
        }
        if (price != null && (price < 1000 || price > 500_000_000)) price = null;
        boolean negotiable = price == null && (text.contains("kelish") || text.contains("dogovor") || text.contains("kelishamiz"));

        // ---- Sana ----
        LocalDate date = null;
        if (text.contains(" bugun") || text.contains(" segodnya")) date = today;
        else if (text.contains(" ertaga") || text.contains(" zavtra")) date = today.plusDays(1);
        else if (text.contains(" indinga") || text.contains(" poslezavtra")) date = today.plusDays(2);
        if (date == null && (m = DAY_MONTH_NAME.matcher(rest)).find()) {
            date = future(today, Integer.parseInt(m.group(1)), monthOf(m.group(2)));
        }
        if (date == null && (m = DAY_MONTH_NUM.matcher(rest)).find()) {
            date = future(today, Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)));
        }

        BodyType body = (BodyType) firstWord(words, used, BODY_WORDS);
        CargoType cargo = (CargoType) firstWord(words, used, CARGO_WORDS);
        if (cargo == null) cargo = CargoType.GENERAL;

        // So'zlar orasida faqat probel (vergul va nuqtalarsiz) — "olaman," ham "olaman" deb topilsin
        String plain = " " + text.replaceAll("[^a-z0-9 ]", " ").replaceAll("\\s+", " ").trim() + " ";

        // ---- Turi: yo'lovchi ("2 kishi", "yo'lovchi"), pochta yoki yuk ----
        // Og'ir narsa (50 kg dan ortiq) — har doim yuk; yuk belgisi bor matnda "2 ta odam" — yuklovchilar, yo'lovchi emas.
        boolean heavy = weight != null && weight > HEAVY_KG;
        boolean cargoSignal = heavy || cargo != CargoType.GENERAL || plain.contains(" gruz")
                || body == BodyType.TRUCK || body == BodyType.BORT || body == BodyType.CONTAINER;
        OrderKind kind = OrderKind.CARGO;
        Integer seats = null;
        if (!cargoSignal && (m = SEATS.matcher(text)).find()) {
            int n = Integer.parseInt(m.group(1));
            if (n >= 1 && n <= 30) seats = n;
        }
        if (!heavy && (seats != null || hasWord(words, used, PASSENGER_WORDS))) {
            kind = OrderKind.PASSENGER;
            weight = null;   // yo'lovchida og'irlik yo'q
        } else if (!heavy && hasWord(words, used, PARCEL_WORDS)) {
            kind = OrderKind.PARCEL;
        }

        // ---- Haydovchining e'loni: bo'sh o'rinlar soni ("3 ta bo'sh joy bor", "2 kishi olaman") ----
        boolean offer = !REQUEST.matcher(plain).find() && Arrays.stream(OFFER_WORDS).anyMatch(plain::contains);
        if (offer && (m = OFFER_SEATS.matcher(text)).find()) {
            int n = Integer.parseInt(m.group(1));
            seats = n >= 1 && n <= 30 ? n : seats;
        }
        if (!offer && kind == OrderKind.PASSENGER && seats == null) seats = 1;

        String firstLine = cleanOriginal.split("\\R", 2)[0].trim();
        String title = firstLine.length() > 120 ? firstLine.substring(0, 117) + "…" : firstLine;
        if (title.length() < 3) title = kind == OrderKind.PARCEL ? "Pochta" : "Yuk";
        String description = cleanOriginal.length() > 1500 ? cleanOriginal.substring(0, 1500) : cleanOriginal;
        return new Parsed(from, to, weight, price, negotiable, date, body, cargo, title, description, kind, seats, offer);
    }

    /** Joy nomi bo'lmagan so'zlardan biri berilgan o'zaklardan biri bilan boshlanadimi */
    private static boolean hasWord(String[] words, boolean[] used, String[] stems) {
        for (int i = 0; i < words.length; i++) {
            if (used[i]) continue;
            for (String st : stems) if (words[i].startsWith(st)) return true;
        }
        return false;
    }

    /** O'zbekcha qo'shimchalar: "Toshkentdan", "Samarqandga", "Buxorogacha" (va ruscha kelishiklar: "Tashkenta") */
    private static final String[] FROM_SUFFIX = {"dan", "tan"};
    private static final String[] TO_SUFFIX = {"gacha", "kacha", "qacha", "ga", "ka", "qa"};
    private static final String[] OTHER_SUFFIX = {"dagi", "da", "ning", "lik", "liklar", "a", "e", "u"};

    /** @return -1 — mos emas, 0 — mos (yo'nalishi noma'lum), 1 — "-dan" (qayerdan), 2 — "-ga" (qayerga) */
    private static int matchAt(String[] words, int i, String[] alias) {
        if (i + alias.length > words.length) return -1;
        for (int k = 0; k < alias.length - 1; k++) if (!words[i + k].equals(alias[k])) return -1;
        String w = words[i + alias.length - 1], last = alias[alias.length - 1];
        if (w.equals(last)) return 0;
        if (!w.startsWith(last)) return -1;
        String rest = w.substring(last.length());
        for (String s : FROM_SUFFIX) if (rest.equals(s)) return 1;
        for (String s : TO_SUFFIX) if (rest.equals(s)) return 2;
        for (String s : OTHER_SUFFIX) if (rest.equals(s)) return 0;
        return -1;
    }

    /** b — a viloyatining aniq tumani (a — butun viloyat) */
    private static boolean refines(Place a, Place b) {
        return a.districtId() == null && b.districtId() != null && a.regionId() == b.regionId();
    }

    /** b — a shahri joylashgan viloyatning boshqa tumani ("Andijon Asaka", "Buxoro Kogon") */
    private boolean narrows(Place a, Place b) {
        return a.districtId() != null && cities.contains(a.districtId()) && b.districtId() != null
                && a.regionId() == b.regionId() && !a.districtId().equals(b.districtId());
    }

    private static boolean identical(Place a, Place b) {
        return a.regionId() == b.regionId() && Objects.equals(a.districtId(), b.districtId());
    }

    /** Bir xil joy: aynan o'sha tuman yoki bir viloyat (tumani noma'lum) */
    private static boolean same(Place a, Place b) {
        if (a == null || b == null) return false;
        if (a.regionId() != b.regionId()) return false;
        return a.districtId() == null || b.districtId() == null || a.districtId().equals(b.districtId());
    }

    private static Object firstWord(String[] words, boolean[] used, Object[][] table) {
        for (int i = 0; i < words.length; i++) {
            if (used[i]) continue;   // joy nomi (masalan "Olmaliq") yuk turi deb o'qilmasin
            String w = words[i];
            for (Object[] row : table) {
                String key = (String) row[0];
                if (key.length() <= 3 ? w.equals(key) : w.startsWith(key)) return row[1];
            }
        }
        return null;
    }

    private static int monthOf(String name) {
        for (int i = 0; i < MONTHS.length; i++) if (name.startsWith(MONTHS[i])) return i + 1;
        return 0;
    }

    /** Kun va oy — bugundan oldin bo'lsa keyingi yilga; noto'g'ri bo'lsa null */
    private static LocalDate future(LocalDate today, int day, int month) {
        if (month < 1 || month > 12 || day < 1 || day > 31) return null;
        try {
            LocalDate d = LocalDate.of(today.getYear(), month, day);
            if (d.isBefore(today)) d = d.plusYears(1);
            return d.isAfter(today.plusDays(90)) ? null : d;
        } catch (Exception e) {
            return null;
        }
    }

    private static double num(String s) {
        return Double.parseDouble(s.replace(',', '.'));
    }

    private static String cut(String s, Matcher m) {
        return s.substring(0, m.start()) + " " + s.substring(m.end());
    }
}
