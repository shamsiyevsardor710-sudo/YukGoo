package uz.yukgo.channel;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Buyurtma yaratilganda yoki holati o'zgarganda chaqiriladi. Kanallar faqat o'zgarish bazaga muvaffaqiyatli
 * yozilgandan keyin yangilanadi (xato bo'lib hammasi bekor bo'lsa, kanalga hech narsa chiqmaydi).
 */
@Component
@RequiredArgsConstructor
public class ChannelHooks {
    private final ChannelPublisher publisher;

    public void orderChanged(Long orderId) {
        if (orderId == null) return;
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    publisher.sync(orderId);
                }
            });
        } else {
            publisher.sync(orderId);
        }
    }
}
