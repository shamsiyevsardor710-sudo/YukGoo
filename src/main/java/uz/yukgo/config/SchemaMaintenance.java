package uz.yukgo.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * PostgreSQL'da Hibernate enum ustunlariga "status IN ('NEW', 'ACCEPTED', …)" ko'rinishidagi CHECK cheklovini qo'yadi,
 * ddl-auto=update esa keyin bu cheklovni yangilamaydi. Natijada kodga yangi qiymat qo'shilsa (masalan, yangi buyurtma
 * holati yoki hamyon amali), uni bazaga yozishda xato chiqadi. Shuning uchun har ishga tushishda shunday cheklovlar
 * olib tashlanadi — qiymatni baribir faqat ilova yozadi va u enum'dagi qiymatdan boshqasini yozmaydi.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SchemaMaintenance {
    private final JdbcTemplate jdbc;
    private final Environment env;

    /** Joriy sxemadagi enum ro'yxati bilan tuzilgan CHECK cheklovlari (boshqa CHECK'lar tegilmaydi) */
    static final String ENUM_CHECKS_SQL = """
            SELECT t.relname AS tbl, c.conname AS name
            FROM pg_constraint c
            JOIN pg_class t ON t.oid = c.conrelid
            JOIN pg_namespace n ON n.oid = t.relnamespace
            WHERE c.contype = 'c'
              AND n.nspname = current_schema()
              AND pg_get_constraintdef(c.oid) LIKE '%= ANY (%ARRAY[%'
            """;

    @EventListener(ApplicationReadyEvent.class)
    public void dropEnumChecks() {
        if (!env.getProperty("spring.datasource.url", "").startsWith("jdbc:postgresql:")) return;
        try {
            List<Map<String, Object>> rows = jdbc.queryForList(ENUM_CHECKS_SQL);
            for (Map<String, Object> r : rows) {
                jdbc.execute("ALTER TABLE " + ident(r.get("tbl")) + " DROP CONSTRAINT IF EXISTS " + ident(r.get("name")));
            }
            if (!rows.isEmpty()) {
                log.info("{} ta enum CHECK cheklovi olib tashlandi (yangi qiymatlar bazaga yozilishi uchun)", rows.size());
            }
        } catch (Exception e) {
            log.warn("Enum CHECK cheklovlarini tekshirib bo'lmadi: {}", e.getMessage());
        }
    }

    /** PostgreSQL identifikatori qo'shtirnoqda (nomdagi qo'shtirnoq ikkilantiriladi) */
    static String ident(Object name) {
        return "\"" + String.valueOf(name).replace("\"", "\"\"") + "\"";
    }
}
