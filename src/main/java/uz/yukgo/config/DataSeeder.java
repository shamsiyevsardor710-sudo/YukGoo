package uz.yukgo.config;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.io.ClassPathResource;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import uz.yukgo.common.Phones;
import uz.yukgo.location.District;
import uz.yukgo.location.DistrictRepository;
import uz.yukgo.location.Region;
import uz.yukgo.location.RegionRepository;
import uz.yukgo.user.Role;
import uz.yukgo.user.User;
import uz.yukgo.user.UserRepository;

import java.io.InputStream;
import java.util.List;

/** Birinchi ishga tushishda viloyat/tumanlar va ega (owner) hisobini yaratadi. */
@Slf4j
@Component
@RequiredArgsConstructor
public class DataSeeder implements ApplicationRunner {
    private final RegionRepository regionRepository;
    private final DistrictRepository districtRepository;
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final ObjectMapper objectMapper;
    private final AppProperties props;

    record RegionSeed(String name, Double lat, Double lng, List<String> districts) {}

    @Override
    @Transactional
    public void run(ApplicationArguments args) throws Exception {
        seedRegions();
        seedOwner();
    }

    private void seedRegions() throws Exception {
        if (regionRepository.count() > 0) return;
        try (InputStream in = new ClassPathResource("data/regions.json").getInputStream()) {
            List<RegionSeed> seeds = objectMapper.readValue(in, new TypeReference<>() {});
            int order = 0;
            for (RegionSeed s : seeds) {
                Region r = new Region();
                r.setName(s.name());
                r.setLat(s.lat());
                r.setLng(s.lng());
                r.setSortOrder(order++);
                regionRepository.save(r);
                for (String dn : s.districts()) {
                    District d = new District();
                    d.setRegion(r);
                    d.setName(dn);
                    districtRepository.save(d);
                }
            }
            log.info("{} ta viloyat va tumanlar bazaga yozildi", seeds.size());
        }
    }

    /** application.yml dagi sinov paroli — hammaga ochiq */
    static final String DEV_OWNER_PASSWORD = "owner12345";

    private void seedOwner() {
        if (userRepository.existsByRole(Role.OWNER)) return;
        var o = props.owner();
        String password = o.password() == null ? "" : o.password();
        if (!props.devMode() && (password.isBlank() || DEV_OWNER_PASSWORD.equals(password))) {
            // Bo'sh yoki ochiq parol bilan ega hisobi yaratilsa — begona odam ega bo'lib kirishi mumkin
            log.error("Ega hisobi yaratilmadi: OWNER_PASSWORD berilmagan yoki sinov qiymatida. "
                    + "Railway → Variables'da OWNER_PASSWORD ga kuchli parol yozing va qayta deploy qiling.");
            return;
        }
        String phone = Phones.normalize(o.phone());
        User owner = userRepository.findByPhone(phone).orElseGet(User::new);
        owner.setPhone(phone);
        owner.setNames(o.firstName(), o.lastName());
        owner.setPasswordHash(passwordEncoder.encode(o.password()));
        owner.setRole(Role.OWNER);
        owner.setRegion(regionRepository.findAllByOrderBySortOrderAsc().stream().findFirst().orElse(null));
        userRepository.save(owner);
        log.warn("Ega hisobi yaratildi: {} ({}). Parolni darhol o'zgartiring!", owner.getFullName(), Phones.mask(phone));
    }
}
