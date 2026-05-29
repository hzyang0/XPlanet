package com.xplanet.common.auth;

import com.xplanet.common.exception.BizException;
import com.xplanet.common.response.ErrorCode;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

/**
 * 鉴权拦截器:从 Authorization 头取 token,校验通过则把 userId 放入 UserContext。
 *
 * <p>关键设计:鉴权在「每个服务自己的拦截器」里做,而不是放网关。
 * 因为 token 是无状态的(签名自校验),任何服务实例都能独立验证,
 * 不需要网关集中处理,也就不需要为了鉴权而引入网关这个额外组件。
 *
 * <p>token 的无状态性同时支撑了「应用多实例水平扩展」——
 * 请求落到哪个实例都能独立鉴权,无需共享 session。
 */
public class AuthInterceptor implements HandlerInterceptor {

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        // 读操作(GET)免登录,放行;只对写操作(POST/PUT/DELETE)鉴权
        if ("GET".equalsIgnoreCase(request.getMethod())) {
            return true;
        }
        String auth = request.getHeader("Authorization");
        String token = (auth != null && auth.startsWith("Bearer ")) ? auth.substring(7) : auth;
        Long userId = TokenUtil.verify(token);
        if (userId == null) {
            throw new BizException(ErrorCode.USER_NOT_LOGIN);
        }
        UserContext.set(userId);
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response,
                                Object handler, Exception ex) {
        UserContext.clear(); // 防止 ThreadLocal 内存泄漏
    }
}
