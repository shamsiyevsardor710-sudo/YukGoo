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
import java.util.regex.Pattern;

/**
 * Hibernate enum ustunlarini yaratganda qiymatlar ro'yxatini bazaga «muhrlaydi»: PostgreSQL'da — "status IN ('NEW', …)"
 * ko'rinishidagi CHECK cheklovi, H2'da (Hibernate 6.5+) — H2'ning o'z ENUM turi. ddl-auto=update keyin buni yangilamaydi,
 * natijada kodga yangi qiymat qo'shilsa (masalan, yangi mashina turi yoki hamyon amali), uni bazaga yozishda xato chiqadi.
 * Shuning uchun har ishga tushishda PostgreSQL'da bunday cheklovlar olib tashlanadi, H2'da enum ustunlari oddiy matnga
 * (VARCHAR) o'tkaziladi — qiymatni baribir faqat ilova yozadi va u enum'dagi qiymatdan boshqasini yozmaydi.
 * Keyin ma'lumotlar yangi tuzilishga moslanadi (eski e'lonlar — «yuk», eski mashina turlari — hozirgi 6 turdan biri).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SchemaMaintenance {
    private final JdbcTemplate jdbc;
    private final Environment env;

    /** PostgreSQL: joriy sxemadagi enum ro'yxati bilan tuzilgan CHECK cheklovlari (boshqa CHECK'lar tegilmaydi) */
    static final String ENUM_CHECKS_SQL = """
            SELECT t.relname AS tbl, c.conname AS name
            FROM pg_constraint c
            JOIN pg_class t ON t.oid = c.conrelid
            JOIN pg_namespace n ON n.oid = t.relnamespace
            WHERE c.contype = 'c'
              AND n.nspname = current_schema()
              AND pg_get_constraintdef(c.oid) LIKE '%= ANY (%ARRAY[%'
            """;

    /** H2: barcha CHECK cheklovlari matni bilan — enum ko'rinishidagilari {@link #H2_ENUM_CHECK} bilan ajratiladi */
    static final String H2_CHECKS_SQL = """
            SELECT tc.TABLE_NAME AS tbl, tc.CONSTRAINT_NAME AS name, cc.CHECK_CLAUSE AS clause
            FROM INFORMATION_SCHEMA.TABLE_CONSTRAINTS tc
            JOIN INFORMATION_SCHEMA.CHECK_CONSTRAINTS cc
              ON cc.CONSTRAINT_SCHEMA = tc.CONSTRAINT_SCHEMA AND cc.CONSTRAINT_NAME = tc.CONSTRAINT_NAME
            WHERE tc.CONSTRAINT_TYPE = 'CHECK' AND tc.TABLE_SCHEMA = SCHEMA()
            """;

    /** H2: joriy sxemadagi ENUM turidagi ustunlar (Hibernate 6.5+ enum ustunlarini H2'da shunday yaratadi) */
    static final String H2_ENUM_COLUMNS_SQL = """
            SELECT TABLE_NAME AS tbl, COLUMN_NAME AS col
            FROM INFORMATION_SCHEMA.COLUMNS
            WHERE TABLE_SCHEMA = SCHEMA() AND UPPER(DATA_TYPE) = 'ENUM'
            """;

    /** H2 saqlagan enum cheklovi: "status" IN('NEW', 'ACCEPTED') — faqat bitta ustun va matnli qiymatlar ro'yxati */
    static final Pattern H2_ENUM_CHECK = Pattern.compile(
            "(?is)^\\s*\\(?\\s*\"?[A-Za-z0-9_]+\"?\\s+IN\\s*\\(\\s*'[^']*'(\\s*,\\s*'[^']*')*\\s*\\)\\s*\\)?\\s*$");

    /** Ma'lumotlarni yangi tuzilishga moslash (har ishga tushishda; ikkinchi martadan boshlab 0 qator o'zgaradi) */
    static final List<String> DATA_FIXES = List.of(
            // Turlar paydo bo'lishidan oldingi e'lonlar — yuk
            "UPDATE orders SET kind = 'CARGO' WHERE kind IS NULL",
            // Eski mashina turlari hozirgi 6 turdan biriga (Tentli, Refrijerator, Samosval... — yuk mashinasi; Damas/Labo — furgon)
            "UPDATE users SET vehicle_body_type = 'TRUCK' WHERE vehicle_body_type IN ('TENT', 'REFRIGERATOR', 'ISOTHERM', 'SAMOSVAL', 'TRAL', 'TANKER')",
            "UPDATE users SET vehicle_body_type = 'FURGON' WHERE vehicle_body_type = 'MINIVAN'",
            "UPDATE orders SET required_body_type = 'TRUCK' WHERE required_body_type IN ('TENT', 'REFRIGERATOR', 'ISOTHERM', 'SAMOSVAL', 'TRAL', 'TANKER')",
            "UPDATE orders SET required_body_type = 'FURGON' WHERE required_body_type = 'MINIVAN'");

    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        String url = env.getProperty("spring.datasource.url", "");
        if (url.startsWith("jdbc:postgresql:")) dropPostgresEnumChecks();
        else if (url.startsWith("jdbc:h2:")) {
            convertH2EnumColumns();
            dropH2EnumChecks();
        }
        else return;
        fixData();
    }

    void dropPostgresEnumChecks() {
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

    /**
     * H2: ENUM ustunlari VARCHAR'ga o'tkaziladi (ma'lumot, NOT NULL, standart qiymat va indekslar saqlanadi).
     * Aks holda ro'yxatda yo'q yangi qiymat (masalan, TRUCK) na yoziladi, na "IN (...)" so'rovida ishlatiladi.
     */
    void convertH2EnumColumns() {
        try {
            int n = 0;
            for (Map<String, Object> r : jdbc.queryForList(H2_ENUM_COLUMNS_SQL)) {
                try {
                    jdbc.execute("ALTER TABLE " + ident(r.get("tbl")) + " ALTER COLUMN " + ident(r.get("col")) + " SET DATA TYPE VARCHAR(255)");
                    n++;
                } catch (Exception e) {
                    log.warn("Enum ustunini matnga o'tkazib bo'lmadi ({}.{}): {}", r.get("tbl"), r.get("col"), e.getMessage());
                }
            }
            if (n > 0) log.info("{} ta enum ustuni matnga o'tkazildi (yangi qiymatlar bazaga yozilishi uchun)", n);
        } catch (Exception e) {
            log.warn("Enum ustunlarini tekshirib bo'lmadi: {}", e.getMessage());
        }
    }

    void dropH2EnumChecks() {
        try {
            int n = 0;
            for (Map<String, Object> r : jdbc.queryForList(H2_CHECKS_SQL)) {
                Object clause = r.get("clause");
                if (clause == null || !H2_ENUM_CHECK.matcher(clause.toString()).matches()) continue;
                jdbc.execute("ALTER TABLE " + ident(r.get("tbl")) + " DROP CONSTRAINT IF EXISTS " + ident(r.get("name")));
                n++;
            }
            if (n > 0) log.info("{} ta enum CHECK cheklovi olib tashlandi (yangi qiymatlar bazaga yozilishi uchun)", n);
        } catch (Exception e) {
            log.warn("Enum CHECK cheklovlarini tekshirib bo'lmadi: {}", e.getMessage());
        }
    }

    void fixData() {
        for (String sql : DATA_FIXES) {
            try {
                int n = jdbc.update(sql);
                if (n > 0) log.info("Ma'lumotlar yangilandi ({} qator): {}", n, sql);
            } catch (Exception e) {
                log.warn("Ma'lumotlarni yangilab bo'lmadi ({}): {}", sql, e.getMessage());
            }
        }
    }

    /** Identifikator qo'shtirnoqda (nomdagi qo'shtirnoq ikkilantiriladi) */
    static String ident(Object name) {
        return "\"" + String.valueOf(name).replace("\"", "\"\"") + "\"";
    }
}
