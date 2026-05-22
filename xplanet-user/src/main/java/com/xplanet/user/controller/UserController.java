package com.xplanet.user.controller;

import com.xplanet.common.exception.BizException;
import com.xplanet.common.response.ErrorCode;
import com.xplanet.common.response.R;
import com.xplanet.user.entity.User;
import com.xplanet.user.mapper.UserMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

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
}
