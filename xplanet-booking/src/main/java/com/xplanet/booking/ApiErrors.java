package com.xplanet.booking;

import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.method.annotation.HandlerMethodValidationException;

@RestControllerAdvice
public class ApiErrors {
  private static final Logger log = LoggerFactory.getLogger(ApiErrors.class);

  @ExceptionHandler(Problem.class)
  ResponseEntity<?> problem(Problem e) {
    return response(e.status(), e.getMessage());
  }

  @ExceptionHandler({
    MethodArgumentNotValidException.class,
    HttpMessageNotReadableException.class,
    HandlerMethodValidationException.class,
    org.springframework.web.bind.ServletRequestBindingException.class,
    jakarta.validation.ConstraintViolationException.class,
    org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class
  })
  ResponseEntity<?> invalid(Exception e) {
    return response(400, "参数格式或长度不正确，请检查输入（时间使用 UTC）");
  }

  @ExceptionHandler({
    org.springframework.web.servlet.resource.NoResourceFoundException.class,
    org.springframework.web.servlet.NoHandlerFoundException.class
  })
  ResponseEntity<?> notFound(Exception e) {
    return response(404, "请求的资源不存在");
  }

  @ExceptionHandler(org.springframework.web.HttpRequestMethodNotSupportedException.class)
  ResponseEntity<?> methodNotAllowed(Exception e) {
    return response(405, "该接口不支持此请求方式");
  }

  @ExceptionHandler(org.springframework.web.HttpMediaTypeNotSupportedException.class)
  ResponseEntity<?> unsupportedMedia(Exception e) {
    return response(415, "请使用 application/json 提交请求");
  }

  @ExceptionHandler(DataIntegrityViolationException.class)
  ResponseEntity<?> integrity(Exception e) {
    return response(409, "数据发生冲突，请刷新后重试；请求标识不能用于不同操作");
  }

  @ExceptionHandler(TransientDataAccessException.class)
  ResponseEntity<?> transientError(Exception e) {
    return response(503, "资源繁忙，请使用同一请求标识重试");
  }

  @ExceptionHandler(Exception.class)
  ResponseEntity<?> unexpected(Exception e) {
    log.error("Unexpected request failure", e);
    return response(500, "服务处理失败，请联系管理员");
  }

  private ResponseEntity<?> response(int status, String message) {
    return ResponseEntity.status(status).body(Map.of("status", status, "message", message));
  }
}
