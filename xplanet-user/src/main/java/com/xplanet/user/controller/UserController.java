package com.xplanet.user.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.xplanet.common.auth.TokenUtil;
import com.xplanet.common.exception.BizException;
import com.xplanet.common.response.ErrorCode;
import com.xplanet.common.response.R;
import com.xplanet.user.entity.User;
import com.xplanet.user.mapper.UserMapper;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/user")
@RequiredArgsConstructor
public class UserController {

    private final UserMapper userMapper;

    @GetMapping("/{id}")
    public R<User> get(@PathVariable Long id) {
        User u = userMapper.selectById(id);
        if (u == null) throw new BizException(ErrorCode.USER_NOT_FOUND);
        return R.ok(u);
    }

    /**
     * 登录:校验用户名(demo 简化,不校验密码),签发 token。
     * 生产应校验密码哈希(BCrypt)。这里聚焦演示 token 鉴权链路。
     *
     * <p>限流防撞库:同一 IP 每分钟最多 5 次登录尝试。
     */
    @PostMapping("/login")
    @com.xplanet.common.ratelimit.RateLimit(key = "user_login", limit = 5, windowSeconds = 60)
    public R<Map<String, Object>> login(@RequestBody LoginRequest req) {
        User u = userMapper.selectOne(new LambdaQueryWrapper<User>()
                .eq(User::getUsername, req.getUsername()));
        if (u == null) {
            throw new BizException(ErrorCode.USER_NOT_FOUND);
        }
        String token = TokenUtil.issue(u.getId());
        Map<String, Object> data = new HashMap<>();
        data.put("token", token);
        data.put("userId", u.getId());
        data.put("nickname", u.getNickname());
        return R.ok(data);
    }

    @Data
    public static class LoginRequest {
        private String username;
        private String password;  // demo 未校验
    }
}
