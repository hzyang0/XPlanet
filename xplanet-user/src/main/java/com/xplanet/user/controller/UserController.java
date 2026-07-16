package com.xplanet.user.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.xplanet.api.vo.UserProfileVO;
import com.xplanet.common.auth.TokenService;
import com.xplanet.common.exception.BizException;
import com.xplanet.common.response.ErrorCode;
import com.xplanet.common.response.R;
import com.xplanet.user.entity.User;
import com.xplanet.user.mapper.UserMapper;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;

import javax.validation.Valid;
import javax.validation.constraints.NotBlank;
import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/user")
@RequiredArgsConstructor
public class UserController {

    /** 与 demo 数据相同成本的固定哈希，避免“用户不存在”路径明显更快。 */
    private static final String DUMMY_PASSWORD_HASH =
            "{bcrypt}$2a$10$dXJ3SW6G7P50lGmMkkmwe.20cQQubK3.HZWzG3YB1tlRy.fqvM/BG";

    private final UserMapper userMapper;
    private final PasswordEncoder passwordEncoder;
    private final TokenService tokenService;

    @GetMapping("/{id}")
    public R<UserProfileVO> get(@PathVariable Long id) {
        User u = userMapper.selectById(id);
        if (u == null) throw new BizException(ErrorCode.USER_NOT_FOUND);
        UserProfileVO profile = new UserProfileVO();
        profile.setId(u.getId());
        profile.setUsername(u.getUsername());
        profile.setNickname(u.getNickname());
        profile.setAvatar(u.getAvatar());
        return R.ok(profile);
    }

    /**
     * 登录:校验用户名与密码哈希，成功后签发短期 JWT。
     *
     * <p>限流防撞库:同一 IP 每分钟最多 5 次登录尝试。
     */
    @PostMapping("/login")
    @com.xplanet.common.ratelimit.RateLimit(key = "user_login", limit = 5, windowSeconds = 60)
    public R<Map<String, Object>> login(@Valid @RequestBody LoginRequest req) {
        User u = userMapper.selectOne(new LambdaQueryWrapper<User>()
                .eq(User::getUsername, req.getUsername()));
        String storedHash = u == null || u.getPasswordHash() == null
                ? DUMMY_PASSWORD_HASH : u.getPasswordHash();
        boolean passwordMatches = passwordEncoder.matches(req.getPassword(), storedHash);
        if (u == null || !passwordMatches) {
            throw new BizException(ErrorCode.USER_CREDENTIALS_INVALID);
        }
        String token = tokenService.issue(u.getId());
        Map<String, Object> data = new HashMap<>();
        data.put("token", token);
        data.put("userId", u.getId());
        data.put("nickname", u.getNickname());
        return R.ok(data);
    }

    @Data
    public static class LoginRequest {
        @NotBlank(message = "用户名不能为空")
        private String username;
        @NotBlank(message = "密码不能为空")
        private String password;
    }
}
