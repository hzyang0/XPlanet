package com.xplanet.common.response;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 错误码规范:
 * 1xxx 通用 / 2xxx 用户 / 3xxx 文章 / 5xxx 限流 / 9xxx 系统
 */
@Getter
@AllArgsConstructor
public enum ErrorCode {
    PARAM_INVALID(1001, "参数错误"),

    USER_NOT_LOGIN(2001, "未登录"),
    USER_NOT_FOUND(2002, "用户不存在"),
    USER_CREDENTIALS_INVALID(2003, "用户名或密码错误"),

    ARTICLE_NOT_FOUND(3001, "文章不存在"),
    ARTICLE_SERVICE_UNAVAILABLE(3002, "文章服务暂时不可用"),
    COMMENT_PARENT_INVALID(3003, "父评论不存在、不属于当前文章或不是顶级评论"),

    FLOW_BLOCKED(5001, "请求过于频繁,请稍后再试"),

    SYSTEM_ERROR(9999, "系统内部错误");

    private final int code;
    private final String msg;
}
