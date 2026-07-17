package com.xplanet.common.auth;

import com.xplanet.common.exception.BizException;
import com.xplanet.common.response.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

/**
 * 鉴权拦截器:从 Authorization 头取 token,校验通过则把 userId 放入 UserContext。
 *
 * <p>关键设计:Gateway 做外部请求的第一层快速拦截，业务服务仍独立验签。
 * 不能只信任网关传来的用户头，因为内部误调用、配置错误或未来新增入口都可能绕过网关；
 * 无状态 JWT 让每个服务实例都能用相同密钥独立验证，而不需要共享 Session。
 *
 * <p>token 的无状态性同时支撑了「应用多实例水平扩展」——
 * 请求落到哪个实例都能独立鉴权,无需共享 session。
 */
@Component
@RequiredArgsConstructor
public class AuthInterceptor implements HandlerInterceptor {

    private final TokenService tokenService;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        // 读操作(GET)免登录,放行;只对写操作(POST/PUT/DELETE)鉴权
        if ("GET".equalsIgnoreCase(request.getMethod())) {
            return true;
        }
        String auth = request.getHeader("Authorization");
        String token = (auth != null && auth.startsWith("Bearer ")) ? auth.substring(7) : auth;
        Long userId = tokenService.verify(token);
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
