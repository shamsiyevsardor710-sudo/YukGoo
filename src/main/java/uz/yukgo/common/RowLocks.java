package uz.yukgo.common;

import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Qatorni bloklab, bazadagi joriy holatni qayta o'qish (SELECT ... FOR UPDATE + refresh).
 * Oddiy "lock" so'rovi shu tranzaksiyada avval yuklangan obyektni yangilamaydi — eskirgan qiymat ustidan yozilib,
 * parallel o'zgarish yo'qolishi mumkin. Shuning uchun hisoblagich va balans kabi qiymatlar shu yerdan o'qiladi.
 * Chaqirishdan oldin obyektda saqlanmagan o'zgarish bo'lmasligi kerak (refresh ularni bekor qiladi).
 */
@Component
@RequiredArgsConstructor
public class RowLocks {
    private final EntityManager entityManager;

    public <T> T lockFresh(T entity) {
        entityManager.refresh(entity, LockModeType.PESSIMISTIC_WRITE);
        return entity;
    }
}
