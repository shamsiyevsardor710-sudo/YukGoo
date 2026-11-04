package uz.yukgo.push;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import uz.yukgo.security.CurrentUser;

import java.time.Instant;
import java.util.List;

/** Android ilova qurilmasini push uchun ro'yxatdan o'tkazish (kirganda) va o'chirish (chiqqanda) */
@RestController
@RequestMapping("/api/push")
@RequiredArgsConstructor
public class PushController {
    private final PushTokenRepository tokens;
    private final CurrentUser currentUser;

    public record TokenRequest(@NotBlank @Size(max = 512) String token, @Size(max = 16) String platform) {}

    /**
     * Tranzaksiyasiz: ilova birinchi ochilganda bir xil token ikki marta (deyarli bir vaqtda) keladi — ikkinchisi takror
     * qator yozmoqchi bo'lsa, bazadagi noyoblik cheklovi to'xtatadi va bu xato emas (token allaqachon saqlangan).
     */
    @PostMapping("/register")
    public void register(@Valid @RequestBody TokenRequest req) {
        Long me = currentUser.id();
        String token = req.token().trim();
        // Bir qurilma — bitta qator: shu telefonda boshqa hisob bilan kirilgan bo'lsa, endi shu hisobga o'tadi
        List<PushToken> same = tokens.findByToken(token);
        PushToken t = same.isEmpty() ? new PushToken() : same.get(0);
        t.setUserId(me);
        t.setToken(token);
        t.setPlatform(req.platform() == null || req.platform().isBlank() ? "android" : req.platform().trim());
        t.setUpdatedAt(Instant.now());
        try {
            tokens.save(t);
        } catch (DataIntegrityViolationException e) {
            return;   // parallel so'rov shu tokenni hozirgina yozdi
        }
        // Eski telefonlar (ko'pi bilan MAX_DEVICES ta qoladi)
        List<PushToken> mine = tokens.findByUserIdOrderByUpdatedAtDesc(me);
        for (int i = PushService.MAX_DEVICES; i < mine.size(); i++) tokens.delete(mine.get(i));
    }

    @PostMapping("/unregister")
    @Transactional
    public void unregister(@Valid @RequestBody TokenRequest req) {
        Long me = currentUser.id();
        tokens.findByToken(req.token().trim()).stream().filter(t -> t.getUserId().equals(me)).forEach(tokens::delete);
    }
}
