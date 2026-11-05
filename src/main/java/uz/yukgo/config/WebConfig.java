package uz.yukgo.config;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.CacheControl;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.nio.file.Path;

@Configuration
@RequiredArgsConstructor
public class WebConfig implements WebMvcConfigurer {
    private final AppProperties props;

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        String location = Path.of(props.uploadsDir()).toAbsolutePath().normalize().toUri().toString();
        if (!location.endsWith("/")) location += "/";
        registry.addResourceHandler("/uploads/**")
                .addResourceLocations(location)
                .setCachePeriod(3600 * 24 * 7);
        // Ilova kodi (JS va CSS): brauzer va Android ilova har ochilishda yangi versiya bormi tekshiradi — o'zgarmagan bo'lsa
        // 304 (qayta yuklanmaydi). Aks holda yangilanishdan keyin bir muddat eski kod (eski sahifalar) qolib ketishi mumkin
        registry.addResourceHandler("/js/**")
                .addResourceLocations("classpath:/static/js/")
                .setCacheControl(CacheControl.noCache());
        registry.addResourceHandler("/css/**")
                .addResourceLocations("classpath:/static/css/")
                .setCacheControl(CacheControl.noCache());
        // Sahifalar (index.html — ilovaning o'zi, maxfiylik siyosati, shartlar va boshqalar): xuddi shunday tekshiriladi
        registry.addResourceHandler("/*.html")
                .addResourceLocations("classpath:/static/")
                .setCacheControl(CacheControl.noCache());
    }
}
