package com.xplanet.common.response;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 错误码规范:
 * 1xxx 通用 / 2xxx 用户 / 3xxx 文章 / 4xxx 互动 / 5xxx 限流降级 / 9xxx 系统
 */
@Getter
@AllArgsConstructor
public enum ErrorCode {
    SUCCESS(0, "ok"),
    PARAM_INVALID(1001, "参数错误"),
    NOT_FOUND(1404, "资源不存在"),

    USER_NOT_LOGIN(2001, "未登录"),
    USER_NOT_FOUND(2002, "用户不存在"),

    ARTICLE_NOT_FOUND(3001, "文章不存在"),
    ARTICLE_DELETED(3002, "文章已删除"),

    LIKE_DUPLICATE(4001, "重复点赞已忽略"),

    FLOW_BLOCKED(5001, "请求过于频繁,请稍后再试"),
    DEGRADE_FALLBACK(5002, "服务降级,返回缓存数据"),
    SERVICE_BUSY(5003, "服务繁忙,请稍后再试"),

    SYSTEM_ERROR(9999, "系统内部错误");

    private final int code;
    private final String msg;
}
