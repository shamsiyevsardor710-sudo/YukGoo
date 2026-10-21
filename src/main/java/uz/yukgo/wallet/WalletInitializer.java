package uz.yukgo.wallet;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Hamyon qatorini alohida (ichki) tranzaksiyada yaratadi.
 * Ikki parallel so'rov bir vaqtda yaratmoqchi bo'lsa, biri dublikat kalit xatosini oladi —
 * bu faqat shu ichki tranzaksiyani bekor qiladi, asosiy amal (masalan, komissiya yechish) davom etadi.
 */
@Component
@RequiredArgsConstructor
public class WalletInitializer {
    private final WalletRepository walletRepository;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void createIfMissing(Long userId) {
        if (walletRepository.existsById(userId)) return;
        Wallet w = new Wallet();
        w.setUserId(userId);
        walletRepository.saveAndFlush(w);
    }
}
