package uz.yukgo.user;

public enum UserStatus {
    ACTIVE,
    /** Bloklangan — tizimga kira olmaydi, admin blokdan chiqarishi mumkin */
    BLOCKED,
    /** Chiqarib yuborilgan (o'chirilgan) */
    REMOVED
}
