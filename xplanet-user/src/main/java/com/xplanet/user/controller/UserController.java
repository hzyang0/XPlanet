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
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.web.bind.annotation.*;

import javax.validation.Valid;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.Size;
import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/user")
@RequiredArgsConstructor
public class UserController {
    private final UserMapper userMapper;
    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    @GetMapping("/{id}")
    public R<User> get(@PathVariable Long id) {
        User user = userMapper.selectById(id);
        if (user == null) throw new BizException(ErrorCode.USER_NOT_FOUND);
        user.setPasswordHash(null);
        return R.ok(user);
    }

    @PostMapping("/login")
    @com.xplanet.common.ratelimit.RateLimit(key = "user_login", limit = 5, windowSeconds = 60)
    public R<Map<String, Object>> login(@RequestBody LoginRequest req) {
        User user = userMapper.selectOne(new LambdaQueryWrapper<User>().eq(User::getUsername, req.getUsername()));
        if (user == null || req.getPassword() == null || !passwordEncoder.matches(req.getPassword(), user.getPasswordHash())) {
            throw new BizException(2003, "账号或密码错误");
        }
        return R.ok(session(user));
    }

    @PostMapping("/register")
    public R<Map<String, Object>> register(@Valid @RequestBody RegisterRequest req) {
        String username = req.getUsername().trim();
        if (userMapper.selectOne(new LambdaQueryWrapper<User>().eq(User::getUsername, username)) != null) {
            throw new BizException(2004, "账号已存在");
        }
        User user = new User();
        user.setUsername(username);
        user.setNickname(req.getNickname() == null || req.getNickname().isBlank() ? username : req.getNickname().trim());
        user.setPasswordHash(passwordEncoder.encode(req.getPassword()));
        userMapper.insert(user);
        return R.ok(session(user));
    }

    private Map<String, Object> session(User user) {
        Map<String, Object> data = new HashMap<>();
        data.put("token", TokenUtil.issue(user.getId()));
        data.put("userId", user.getId());
        data.put("nickname", user.getNickname());
        return data;
    }

    @Data
    public static class LoginRequest { private String username; private String password; }

    @Data
    public static class RegisterRequest {
        @NotBlank(message = "请输入账号") @Size(min = 3, max = 32, message = "账号长度需为 3-32 位")
        private String username;
        @NotBlank(message = "请输入密码") @Size(min = 6, max = 72, message = "密码长度需为 6-72 位")
        private String password;
        @Size(max = 32, message = "昵称不能超过 32 位") private String nickname;
    }
}
