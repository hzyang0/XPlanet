package com.xplanet.common.response;

import lombok.Data;
import java.io.Serializable;

/**
 * 统一响应封装。
 * <p>设计要点:
 * 1. code 与 HTTP status 解耦,业务错误统一用 200 + 业务码,便于前端拦截处理;
 * 2. 始终返回 data 字段(即使是 null),减少前端 undefined 判断。
 */
@Data
public class R<T> implements Serializable {
    private int code;
    private String msg;
    private T data;
    private long timestamp;

    public static <T> R<T> ok(T data) {
        R<T> r = new R<>();
        r.code = 0;
        r.msg = "ok";
        r.data = data;
        r.timestamp = System.currentTimeMillis();
        return r;
    }

    public static <T> R<T> fail(int code, String msg) {
        R<T> r = new R<>();
        r.code = code;
        r.msg = msg;
        r.timestamp = System.currentTimeMillis();
        return r;
    }

    public static <T> R<T> fail(ErrorCode ec) {
        return fail(ec.getCode(), ec.getMsg());
    }
}
