package com.xplanet.article.service;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.xplanet.common.response.R;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 调用 user 服务获取用户信息的客户端。
 *
 * <h3>这里演示了三个生产级要点:</h3>
 * <ol>
 *   <li><b>服务间调用</b>:用 RestTemplate 调 user 服务的 REST 接口(简单直接,没引入 Feign 增加复杂度)</li>
 *   <li><b>调用结果缓存</b>:用户名很少变,用 Caffeine 本地缓存 5 分钟,避免每次渲染文章都打一次 user 服务</li>
 *   <li><b>降级容错</b>:user 服务不可用时返回兜底名("用户N"),不让文章服务跟着挂——
 *       这是微服务里「依赖隔离」的基本要求,一个服务的故障不应级联拖垮调用方</li>
 * </ol>
 */
@Slf4j
@Component
public class UserClient {

    private final RestTemplate restTemplate = new RestTemplate();

    @Value("${user-service.base-url:http://localhost:8083}")
    private String userServiceBaseUrl;

    /** 用户名本地缓存:userId -> userName,5 分钟过期 */
    private final Cache<Long, String> nameCache = Caffeine.newBuilder()
            .maximumSize(10_000)
            .expireAfterWrite(5, TimeUnit.MINUTES)
            .build();

    /**
     * 获取用户名。先查本地缓存,miss 时调 user 服务,失败则降级。
     */
    public String getUserName(Long userId) {
        if (userId == null) return "匿名用户";
        return nameCache.get(userId, this::fetchFromUserService);
    }

    private String fetchFromUserService(Long userId) {
        try {
            String url = userServiceBaseUrl + "/api/user/" + userId;
            R<Map<String, Object>> resp = restTemplate.exchange(
                    url, HttpMethod.GET, null,
                    new ParameterizedTypeReference<R<Map<String, Object>>>() {}
            ).getBody();

            if (resp != null && resp.getData() != null) {
                Object nickname = resp.getData().get("nickname");
                Object username = resp.getData().get("username");
                if (nickname != null && !nickname.toString().isEmpty()) return nickname.toString();
                if (username != null) return username.toString();
            }
        } catch (Exception e) {
            // 降级:user 服务不可用时不抛异常,返回兜底名,保证文章正常展示
            log.warn("调用 user 服务失败,降级处理 userId={}, err={}", userId, e.getMessage());
        }
        return "用户" + userId;
    }
}
