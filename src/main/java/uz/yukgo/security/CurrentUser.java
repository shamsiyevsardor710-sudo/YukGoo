package uz.yukgo.security;

import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import uz.yukgo.common.ApiException;
import uz.yukgo.user.User;
import uz.yukgo.user.UserRepository;

@Component
@RequiredArgsConstructor
public class CurrentUser {
    private final UserRepository userRepository;

    public Long idOrNull() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof AuthPrincipal p) return p.id();
        return null;
    }

    public Long id() {
        Long id = idOrNull();
        if (id == null) throw ApiException.unauthorized("Tizimga kiring");
        return id;
    }

    /** Joriy tranzaksiyada boshqariladigan User obyekti */
    public User get() {
        return userRepository.findById(id()).orElseThrow(() -> ApiException.unauthorized("Foydalanuvchi topilmadi"));
    }
}
