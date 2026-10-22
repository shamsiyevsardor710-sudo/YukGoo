package uz.yukgo.config;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/** Admin paneldan o'zgartiriladigan oddiy sozlamalar (kalit — qiymat). Railway o'zgaruvchisisiz yoqib-o'chirish uchun. */
@Entity
@Table(name = "app_settings")
@Getter
@Setter
@NoArgsConstructor
public class AppSetting {
    @Id
    @Column(name = "setting_key", length = 60)
    private String key;

    @Column(name = "setting_value", length = 500)
    private String value;

    private Instant updatedAt = Instant.now();

    public AppSetting(String key, String value) {
        this.key = key;
        this.value = value;
    }
}
