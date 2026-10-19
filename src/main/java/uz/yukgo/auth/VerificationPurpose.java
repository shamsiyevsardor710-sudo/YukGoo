package uz.yukgo.auth;

public enum VerificationPurpose {
    REGISTER("ro'yxatdan o'tish"),
    RESET_PASSWORD("parolni tiklash"),
    CHANGE_PHONE("telefon raqamni o'zgartirish");

    private final String label;

    VerificationPurpose(String label) { this.label = label; }

    public String label() { return label; }
}
