package uz.yukgo.common;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    public record ErrorResponse(Instant timestamp, int status, String message, Map<String, String> fields) {}

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ErrorResponse> handleApi(ApiException ex) {
        return build(ex.getStatus(), ex.getMessage(), null);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidation(MethodArgumentNotValidException ex) {
        Map<String, String> fields = new LinkedHashMap<>();
        ex.getBindingResult().getFieldErrors()
                .forEach(e -> fields.putIfAbsent(e.getField(), e.getDefaultMessage()));
        String first = fields.values().stream().findFirst().orElse("Ma'lumotlar noto'g'ri");
        return build(HttpStatus.BAD_REQUEST, first, fields);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponse> handleUnreadable(HttpMessageNotReadableException ex) {
        return build(HttpStatus.BAD_REQUEST, "So'rov formati noto'g'ri", null);
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ErrorResponse> handleDenied(AccessDeniedException ex) {
        return build(HttpStatus.FORBIDDEN, "Bu amal uchun huquqingiz yo'q", null);
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ErrorResponse> handleSize(MaxUploadSizeExceededException ex) {
        return build(HttpStatus.BAD_REQUEST, "Fayl hajmi juda katta (maks. 10 MB)", null);
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ErrorResponse> handleNoResource(NoResourceFoundException ex) {
        return build(HttpStatus.NOT_FOUND, "Topilmadi", null);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleOther(Exception ex) {
        // Spring MVC xatolari (405, 415, yetishmayotgan parametr va h.k.) 500 emas, o'z statusi bilan qaytsin
        if (ex instanceof org.springframework.web.ErrorResponse er && er.getStatusCode().is4xxClientError()) {
            HttpStatus st = HttpStatus.resolve(er.getStatusCode().value());
            return build(st == null ? HttpStatus.BAD_REQUEST : st, "So'rov noto'g'ri", null);
        }
        if (ex instanceof org.springframework.beans.TypeMismatchException) {
            return build(HttpStatus.BAD_REQUEST, "So'rov parametrlari noto'g'ri", null);
        }
        log.error("Kutilmagan xato", ex);
        return build(HttpStatus.INTERNAL_SERVER_ERROR, "Serverda xatolik yuz berdi", null);
    }

    private ResponseEntity<ErrorResponse> build(HttpStatus status, String msg, Map<String, String> fields) {
        return ResponseEntity.status(status).body(new ErrorResponse(Instant.now(), status.value(), msg, fields));
    }
}
