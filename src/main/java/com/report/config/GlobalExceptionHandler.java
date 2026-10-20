package com.report.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.lang.Nullable;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 全局异常处理。
 *
 * <p>缺陷 M-4 根因：原实现只有 {@code @ExceptionHandler(Exception.class)} 一个兜底出口，
 * 而该出口对「所有」异常一律返回 500。Spring MVC 自身的标准异常
 * （{@code NoHandlerFoundException} 404、{@code NoResourceFoundException} 404、
 * {@code HttpRequestMethodNotSupportedException} 405、{@code HttpMediaTypeNotSupportedException} 415、
 * 参数绑定/校验失败 400 等）也被一并改写成 500，
 * 造成「访问不存在的路径返回 500」以及大量无意义的 ERROR 日志与框架栈信息外泄。
 *
 * <p>修复：继承 {@link ResponseEntityExceptionHandler}，让 Spring MVC 标准异常走
 * {@link #handleExceptionInternal} 出口，按其自带状态码返回统一 JSON 结构；
 * 业务异常（{@code IllegalArgumentException} / {@code AccessDeniedException} / {@code SQLException}）
 * 保持原有语义；其余未预期异常仍由兜底处理器返回 500。
 */
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /**
     * Spring MVC 标准异常的统一出口：按其自身状态码返回，不再一律 500。
     * 4xx 记 WARN（属客户端问题，不打栈），5xx 记 ERROR 并保留堆栈。
     */
    @Override
    protected ResponseEntity<Object> handleExceptionInternal(Exception ex, @Nullable Object body,
                                                             HttpHeaders headers, HttpStatusCode statusCode,
                                                             WebRequest request) {
        if (statusCode.is5xxServerError()) {
            log.error("请求处理异常: {} -> {}", request.getDescription(false), statusCode.value(), ex);
        } else {
            log.warn("请求被拒绝: {} -> {} ({})",
                request.getDescription(false), statusCode.value(), ex.getMessage());
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("error", describe(statusCode));
        payload.put("timestamp", LocalDateTime.now().toString());
        return new ResponseEntity<>(payload, headers, statusCode);
    }

    private static String describe(HttpStatusCode sc) {
        return switch (sc.value()) {
            case 400 -> "请求参数不合法";
            case 404 -> "请求的资源不存在";
            case 405 -> "请求方法不被支持";
            case 406 -> "无法返回客户端可接受的响应类型";
            case 415 -> "不支持的请求内容类型";
            default -> sc.is4xxClientError() ? "请求无法处理" : "服务器内部错误，请稍后再试";
        };
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> handleIllegalArgument(IllegalArgumentException e) {
        log.warn("参数错误: {}", e.getMessage());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", e.getMessage());
        body.put("timestamp", LocalDateTime.now().toString());
        return ResponseEntity.badRequest().body(body);
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<Map<String, Object>> handleAccessDenied(AccessDeniedException e) {
        log.warn("权限不足: {}", e.getMessage());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", "权限不足，无法执行此操作");
        body.put("timestamp", LocalDateTime.now().toString());
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(body);
    }

    @ExceptionHandler(SQLException.class)
    public ResponseEntity<Map<String, Object>> handleSqlException(SQLException e) {
        log.error("数据库异常", e);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", "数据库查询失败，请联系管理员");
        body.put("timestamp", LocalDateTime.now().toString());
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(body);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleGenericException(Exception e) {
        log.error("请求处理异常", e);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", "服务器内部错误，请稍后再试");
        body.put("timestamp", LocalDateTime.now().toString());
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(body);
    }
}
