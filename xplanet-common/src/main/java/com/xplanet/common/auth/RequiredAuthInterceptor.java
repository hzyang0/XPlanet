package com.xplanet.common.auth;

import com.xplanet.common.exception.BizException;
import com.xplanet.common.response.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

/**
 * 对读写请求都强制鉴权的拦截器。
 *
 * <p>社区公开读接口继续使用 {@link AuthInterceptor}；研究任务、报告和证据属于用户私有数据，
 * GET 也必须验证 Token，避免复用“GET 放行”规则造成越权读取。
 */
@Component
@RequiredArgsConstructor
public class RequiredAuthInterceptor implements HandlerInterceptor {

    private final TokenService tokenService;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        String auth = request.getHeader("Authorization");
        String token = auth != null && auth.startsWith("Bearer ") ? auth.substring(7) : auth;
        Long userId = tokenService.verify(token);
        if (userId == null || userId <= 0) {
            throw new BizException(ErrorCode.USER_NOT_LOGIN);
        }
        UserContext.set(userId);
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response,
                                Object handler, Exception ex) {
        UserContext.clear();
    }
}
