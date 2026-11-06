package uz.yukgo.order;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import uz.yukgo.common.ApiException;
import uz.yukgo.config.AppSetting;
import uz.yukgo.config.AppSettingRepository;

import java.time.Instant;
import java.util.*;

/**
 * Ega Boshqaruvda o'zgartiradigan buyurtma qoidalari (app_settings jadvalida).
 * <ul>
 *   <li><b>Pochta chegarasi</b>: bitta haydovchi bir vaqtda (olgan, hali yetkazmagan) ko'pi bilan shuncha pochta oladi —
 *   qolgan pochtalar boshqa haydovchilarga ham qolsin. Yetkazgach yoki bekor bo'lgach yana oladi. 0 — cheklovsiz.</li>
 * </ul>
 */
@Component
@RequiredArgsConstructor
public class OrderRules {
    public static final String PARCEL_CAP_KEY = "parcel_cap";
    public static final int DEFAULT_PARCEL_CAP = 2;
    public static final int MAX_PARCEL_CAP = 50;
    /** Faol (olingan, hali yetkazilmagan) buyurtma holatlari */
    public static final List<OrderStatus> ACTIVE = List.of(OrderStatus.ACCEPTED, OrderStatus.IN_TRANSIT);

    private final AppSettingRepository settings;
    private final OrderRepository orders;

    /** 0 — cheklovsiz */
    @Transactional(readOnly = true)
    public int parcelCap() {
        return parse(settings.findById(PARCEL_CAP_KEY).map(AppSetting::getValue).orElse(null));
    }

    /** Sozlamadagi qiymat: bo'sh yoki xato — standart (2), manfiy — standart, juda katta — 50 gacha */
    public static int parse(String raw) {
        if (raw == null || raw.isBlank()) return DEFAULT_PARCEL_CAP;
        try {
            int v = Integer.parseInt(raw.trim());
            return v < 0 ? DEFAULT_PARCEL_CAP : Math.min(v, MAX_PARCEL_CAP);
        } catch (NumberFormatException e) {
            return DEFAULT_PARCEL_CAP;
        }
    }

    @Transactional
    public int setParcelCap(int cap) {
        if (cap < 0 || cap > MAX_PARCEL_CAP) throw ApiException.badRequest("Pochta chegarasi 0 dan " + MAX_PARCEL_CAP + " gacha bo'lsin (0 — cheklovsiz)");
        AppSetting s = settings.findById(PARCEL_CAP_KEY).orElseGet(() -> new AppSetting(PARCEL_CAP_KEY, null));
        s.setValue(String.valueOf(cap));
        s.setUpdatedAt(Instant.now());
        settings.save(s);
        return cap;
    }

    /** Haydovchida hozir nechta pochta bor (olgan, hali yetkazmagan) */
    public long activeParcels(Long driverId) {
        return orders.countByDriverIdAndKindAndStatusIn(driverId, OrderKind.PARCEL, ACTIVE);
    }

    /** Shu haydovchilardan pochta chegarasiga yetganlari (ularga yangi pochta haqida xabar yuborilmaydi) */
    public Set<Long> atParcelCap(Collection<Long> driverIds) {
        int cap = parcelCap();
        if (cap <= 0 || driverIds == null || driverIds.isEmpty()) return Set.of();
        List<Long> ids = new ArrayList<>(new LinkedHashSet<>(driverIds));
        Set<Long> out = new HashSet<>();
        for (int i = 0; i < ids.size(); i += 500) {
            out.addAll(orders.driversWithAtLeast(ids.subList(i, Math.min(ids.size(), i + 500)), OrderKind.PARCEL, ACTIVE, cap));
        }
        return out;
    }

    /** Yana nechta pochta ola oladi: cap — chegara (0 — cheklovsiz: Integer.MAX_VALUE) */
    public static int parcelSlots(int cap, long active) {
        if (cap <= 0) return Integer.MAX_VALUE;
        return (int) Math.max(0, cap - active);
    }

    /** "Bir vaqtda ko'pi bilan 2 ta pochta ..." — haydovchiga */
    public static String parcelCapMessage(int cap) {
        return "Bir vaqtda ko'pi bilan " + cap + " ta pochta olish mumkin — avvalgilarini yetkazgach yana olasiz. "
                + "Qolgan pochtalar boshqa haydovchilarga ham qolsin";
    }
}
