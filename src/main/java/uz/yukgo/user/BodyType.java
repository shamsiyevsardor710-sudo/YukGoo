package uz.yukgo.user;

import java.util.ArrayList;
import java.util.List;

/**
 * Mashina kuzovi turi. Foydalanuvchiga faqat 6 tasi taklif qilinadi ({@link #OFFERED}): yuk mashinasi, yengil mashina,
 * ochiq bortli, furgon, konteyner tashuvchi, boshqa. Qolganlari — avvalgi versiyalarda tanlanganlar (bazada qolgan
 * qiymatlar o'qilishi uchun); ular {@link #canonical()} orqali shu 6 tadan biriga tenglashtiriladi.
 */
public enum BodyType {
    SEDAN,          // Yengil mashina
    MINIVAN,        // (eski) Damas / Labo → FURGON
    FURGON,         // Furgon (Damas, Gazel, Porter)
    TENT,           // (eski) Tentli → TRUCK
    BORT,           // Ochiq bortli
    REFRIGERATOR,   // (eski) Refrijerator → TRUCK
    ISOTHERM,       // (eski) Izotermik → TRUCK
    SAMOSVAL,       // (eski) Samosval → TRUCK
    TRAL,           // (eski) Tral → TRUCK
    TANKER,         // (eski) Sisterna → TRUCK
    CONTAINER,      // Konteyner tashuvchi
    OTHER,          // Boshqa
    TRUCK;          // Yuk mashinasi

    /** Ro'yxatlarda va formalarda ko'rsatiladigan turlar (shu tartibda) */
    public static final List<BodyType> OFFERED = List.of(TRUCK, SEDAN, BORT, FURGON, CONTAINER, OTHER);

    /** Eski (endi taklif qilinmaydigan) turni hozirgi 6 tadan biriga tenglashtiradi */
    public BodyType canonical() {
        return switch (this) {
            case MINIVAN -> FURGON;
            case TENT, REFRIGERATOR, ISOTHERM, SAMOSVAL, TRAL, TANKER -> TRUCK;
            default -> this;
        };
    }

    /** null xavfsiz: turi yo'q bo'lsa null */
    public static BodyType canonical(BodyType b) {
        return b == null ? null : b.canonical();
    }

    /** Bazadan qidirish uchun: shu turga tenglashadigan barcha qiymatlar (eski yozuvlar ham topilsin) */
    public List<BodyType> withLegacy() {
        BodyType c = canonical();
        List<BodyType> all = new ArrayList<>();
        for (BodyType b : values()) if (b.canonical() == c) all.add(b);
        return all;
    }

    /** Ikki tur bir xil hisoblanadimi (eski qiymatlar hisobga olinadi) */
    public static boolean same(BodyType a, BodyType b) {
        return a != null && b != null && a.canonical() == b.canonical();
    }
}
