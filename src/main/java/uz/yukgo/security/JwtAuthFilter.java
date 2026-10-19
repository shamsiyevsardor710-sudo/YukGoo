package uz.yukgo.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import uz.yukgo.user.User;
import uz.yukgo.user.UserRepository;
import uz.yukgo.user.UserStatus;

import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Map;

@Component
@RequiredArgsConstructor
public class JwtAuthFilter extends OncePerRequestFilter {
    private final JwtService jwtService;
    private final UserRepository userRepository;
    private final ObjectMapper objectMapper;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String header = request.getHeader("Authorization");
        if (header != null && header.startsWith("Bearer ")) {
            Long userId = jwtService.parseUserId(header.substring(7));
            if (userId != null) {
                User user = userRepository.findById(userId).orElse(null);
                if (user != null) {
                    if (user.getStatus() != UserStatus.ACTIVE) {
                        String msg = user.getStatus() == UserStatus.BLOCKED
                                ? "Hisobingiz bloklangan" + (user.getBlockReason() != null ? ": " + user.getBlockReason() : "")
                                : "Hisobingiz tizimdan chiqarilgan";
                        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
                        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
                        response.setCharacterEncoding("UTF-8");
                        objectMapper.writeValue(response.getWriter(), Map.of(
                                "timestamp", Instant.now().toString(), "status", 403,
                                "message", msg, "code", "ACCOUNT_" + user.getStatus().name()));
                        return;
                    }
                    var principal = new AuthPrincipal(user.getId(), user.getRole());
                    var auth = new UsernamePasswordAuthenticationToken(principal, null,
                            List.of(new SimpleGrantedAuthority("ROLE_" + user.getRole().name())));
                    SecurityContextHolder.getContext().setAuthentication(auth);
                }
            }
        }
        chain.doFilter(request, response);
    }
}
