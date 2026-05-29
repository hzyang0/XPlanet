package com.xplanet.common.auth;

/**
 * 当前请求的用户上下文(ThreadLocal)。
 * 拦截器校验 token 后把 userId 存这里,业务代码通过 UserContext.getUserId() 获取,
 * 不再依赖前端传 X-User-Id(那种方式可被任意伪造)。
 */
public final class UserContext {
    private static final ThreadLocal<Long> CURRENT = new ThreadLocal<>();
    private UserContext() {}
    public static void set(Long userId) { CURRENT.set(userId); }
    public static Long getUserId() { return CURRENT.get(); }
    public static void clear() { CURRENT.remove(); }
}
