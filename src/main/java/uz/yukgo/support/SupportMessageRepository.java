package uz.yukgo.support;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;

public interface SupportMessageRepository extends JpaRepository<SupportMessage, Long> {
    /** Ega va adminlar uchun: haydovchilardan yoki foydalanuvchilardan kelgan barcha murojaatlar, yangisi birinchi */
    @Query(value = """
            select m from SupportMessage m join fetch m.author
            where m.fromDriver = :fromDriver
            order by m.id desc
            """,
            countQuery = "select count(m) from SupportMessage m where m.fromDriver = :fromDriver")
    Page<SupportMessage> findForStaff(@Param("fromDriver") boolean fromDriver, Pageable pageable);

    /** Xuddi shunday, faqat bitta holatdagilar (masalan, «Yangi») */
    @Query(value = """
            select m from SupportMessage m join fetch m.author
            where m.fromDriver = :fromDriver and m.status = :status
            order by m.id desc
            """,
            countQuery = "select count(m) from SupportMessage m where m.fromDriver = :fromDriver and m.status = :status")
    Page<SupportMessage> findForStaffByStatus(@Param("fromDriver") boolean fromDriver,
                                              @Param("status") SupportMessage.Status status, Pageable pageable);

    long countByFromDriverAndStatus(boolean fromDriver, SupportMessage.Status status);

    /** Foydalanuvchining o'z murojaatlari (oxirgilari) */
    List<SupportMessage> findTop20ByAuthorIdOrderByIdDesc(Long authorId);

    /** Kunlik chegara uchun */
    long countByAuthorIdAndCreatedAtAfter(Long authorId, Instant after);

    /** Hisob o'chirilganda: muallifning barcha murojaatlari (ular haqidagi ega/admin bildirishnomalarini topish uchun) */
    List<SupportMessage> findByAuthorId(Long authorId);

    /** Hisob o'chirilganda */
    @Modifying(flushAutomatically = true)
    @Query("delete from SupportMessage m where m.author.id = :userId")
    int deleteAllForUser(@Param("userId") Long userId);

    /** Javob yozgan admin hisobini o'chirsa — javob qoladi, ismi o'rniga «O'chirilgan foydalanuvchi» */
    @Modifying(flushAutomatically = true)
    @Query("update SupportMessage m set m.repliedByName = :name where m.repliedById = :userId")
    int renameReplier(@Param("userId") Long userId, @Param("name") String name);
}
