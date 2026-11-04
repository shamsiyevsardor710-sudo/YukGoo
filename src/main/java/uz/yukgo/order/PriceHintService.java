package uz.yukgo.order;

import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uz.yukgo.order.OrderDtos.PriceHintDto;

import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;

/**
 * «Bu yo'nalishda odatda 120 000 – 150 000 so'm»: yo'lovchi va pochta e'loni berayotganga shu yo'nalishdagi (ikkala
 * tomonga) so'nggi 4 oydagi e'lonlar narxi. Kelishilgan narx bo'lsa — o'sha. Chetdagi g'alati narxlar (eng arzon va eng
 * qimmat chorak) hisobga olinmaydi. Yuk uchun — masofa va og'irlik bo'yicha hisob (estimate) bor.
 */
@Service
@RequiredArgsConstructor
public class PriceHintService {
    static final int MIN_POINTS = 3;
    static final int MAX_POINTS = 300;
    static final Duration WINDOW = Duration.ofDays(120);
    static final List<OrderStatus> STATUSES = List.of(OrderStatus.NEW, OrderStatus.ACCEPTED, OrderStatus.IN_TRANSIT, OrderStatus.DELIVERED);

    private final OrderRepository orders;

    /** @return null — ma'lumot yetarli emas (yoki yuk, yoki viloyat ichida tumanlari berilmagan) */
    @Transactional(readOnly = true)
    public PriceHintDto hint(OrderKind kind, Long fromRegion, Long toRegion, Long fromDistrict, Long toDistrict) {
        if (kind == null || kind == OrderKind.CARGO || fromRegion == null || toRegion == null) return null;
        // Viloyat ichida masofalar har xil (qo'shni tuman yoki viloyatning narigi chekkasi) — faqat aynan shu tumanlar
        boolean inside = fromRegion.equals(toRegion);
        if (inside && (fromDistrict == null || toDistrict == null)) return null;
        List<Object[]> rows = orders.pricesOnRoute(kind, STATUSES, fromRegion, toRegion, inside ? fromDistrict : null,
                inside ? toDistrict : null, Instant.now().minus(WINDOW), PageRequest.of(0, MAX_POINTS));
        return summarize(rows, kind == OrderKind.PASSENGER);
    }

    /** rows: [seats, narx (jami)] */
    static PriceHintDto summarize(List<Object[]> rows, boolean perPerson) {
        long[] v = rows.stream().mapToLong(r -> {
            long price = r[1] == null ? 0 : ((Number) r[1]).longValue();
            int seats = r[0] == null ? 1 : Math.max(1, ((Number) r[0]).intValue());
            return perPerson ? price / seats : price;
        }).filter(x -> x >= 1000).toArray();
        if (v.length < MIN_POINTS) return null;
        Arrays.sort(v);
        int n = v.length;
        long low = v[(int) Math.floor(0.25 * (n - 1))];
        long high = v[(int) Math.ceil(0.75 * (n - 1))];
        long mid = n % 2 == 1 ? v[n / 2] : (v[n / 2 - 1] + v[n / 2]) / 2;
        long step = high >= 50_000 ? 5_000 : 1_000;
        long lowR = Math.max(step, low / step * step);
        long highR = Math.max(lowR, (high + step - 1) / step * step);
        long typical = Math.min(highR, Math.max(lowR, Math.round((double) mid / step) * step));
        return new PriceHintDto(lowR, highR, typical, n, perPerson);
    }
}
