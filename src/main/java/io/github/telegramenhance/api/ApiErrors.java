package io.github.telegramenhance.api;

import java.util.Map;
import java.util.concurrent.CompletionException;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestControllerAdvice
public class ApiErrors {
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> invalid(IllegalArgumentException error) { return ResponseEntity.badRequest().body(Map.of("message", error.getMessage())); }
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, String>> unreadable() { return ResponseEntity.badRequest().body(Map.of("message", "请求格式无效，请检查设置后重试")); }
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<Map<String, String>> invalidParameter() { return ResponseEntity.badRequest().body(Map.of("message", "筛选或分页参数无效，请检查后重试")); }
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, String>> unavailable(Exception error) {
        Throwable root = error; while (root instanceof CompletionException && root.getCause() != null) root = root.getCause();
        if (root instanceof IllegalArgumentException invalid) return invalid(invalid);
        return ResponseEntity.internalServerError().body(Map.of("message", "本机操作未完成，请检查数据目录和连接状态后重试"));
    }
}
