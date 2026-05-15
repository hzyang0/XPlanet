package com.xplanet.common.exception;

import com.xplanet.common.response.ErrorCode;
import com.xplanet.common.response.R;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(BizException.class)
    public R<Void> handleBiz(BizException e) {
        // 业务异常打 warn 即可,不污染 error 日志
        log.warn("biz exception, code={}, msg={}", e.getCode(), e.getMessage());
        return R.fail(e.getCode(), e.getMessage());
    }

    @ExceptionHandler(Exception.class)
    public R<Void> handleAll(Exception e) {
        log.error("unhandled exception", e);
        return R.fail(ErrorCode.SYSTEM_ERROR);
    }
}
