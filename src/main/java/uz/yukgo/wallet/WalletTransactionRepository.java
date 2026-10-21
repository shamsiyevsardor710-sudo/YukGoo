package uz.yukgo.wallet;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;

public interface WalletTransactionRepository extends JpaRepository<WalletTransaction, Long> {

    Page<WalletTransaction> findByUserIdOrderByCreatedAtDesc(Long userId, Pageable pageable);

    Page<WalletTransaction> findAllByOrderByCreatedAtDesc(Pageable pageable);

    /** Berilgan turlar bo'yicha summalar yig'indisi (yechishlar manfiy, qaytarishlar musbat) */
    @Query("select coalesce(sum(t.amount), 0) from WalletTransaction t where t.type in :types and t.createdAt >= :since")
    long sumAmounts(@Param("types") Collection<TxType> types, @Param("since") Instant since);
}
