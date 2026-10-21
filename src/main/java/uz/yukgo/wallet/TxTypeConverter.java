package uz.yukgo.wallet;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

/**
 * TxType bazada oddiy matn sifatida saqlanadi (enum uchun avtomatik CHECK cheklovisiz).
 * Shunda keyinchalik yangi turlar (masalan, Payme yoki Click orqali to'ldirish) qo'shilganda bazani qo'lda o'zgartirish shart emas.
 */
@Converter
public class TxTypeConverter implements AttributeConverter<TxType, String> {
    @Override
    public String convertToDatabaseColumn(TxType type) {
        return type == null ? null : type.name();
    }

    @Override
    public TxType convertToEntityAttribute(String value) {
        return value == null ? null : TxType.valueOf(value);
    }
}
