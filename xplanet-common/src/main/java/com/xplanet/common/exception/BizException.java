package com.xplanet.common.exception;

import com.xplanet.common.response.ErrorCode;
import lombok.Getter;

@Getter
public class BizException extends RuntimeException {
    private final int code;

    public BizException(ErrorCode ec) {
        super(ec.getMsg());
        this.code = ec.getCode();
    }

    public BizException(int code, String msg) {
        super(msg);
        this.code = code;
    }
}
