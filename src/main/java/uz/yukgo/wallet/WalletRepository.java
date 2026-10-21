package uz.yukgo.wallet;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface WalletRepository extends JpaRepository<Wallet, Long> {

    /** Balansni o'zgartirishdan oldin qatorni bloklash (bir vaqtda ikki yechish bo'lmasligi uchun) */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select w from Wallet w where w.userId = :userId")
    Optional<Wallet> lockByUserId(@Param("userId") Long userId);

    @Query("select coalesce(sum(w.balance), 0) from Wallet w")
    long sumBalances();
}
