package com.xplanet.article.service;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.xplanet.api.vo.UserProfileVO;
import com.xplanet.article.client.UserServiceClient;
import com.xplanet.common.response.R;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

/**
 * 调用 user 服务获取用户信息的客户端。
 *
 * <h3>这里演示了三个生产级要点:</h3>
 * <ol>
 *   <li><b>服务间调用</b>:使用声明式 HTTP 客户端，超时和地址由配置统一管理</li>
 *   <li><b>调用结果缓存</b>:用户名很少变,用 Caffeine 本地缓存 5 分钟,避免每次渲染文章都打一次 user 服务</li>
 *   <li><b>降级容错</b>:user 服务不可用时返回兜底名("用户N"),不让文章服务跟着挂——
 *       这是微服务里「依赖隔离」的基本要求,一个服务的故障不应级联拖垮调用方</li>
 * </ol>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class UserClient {

    private final UserServiceClient userServiceClient;

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
        String cached = nameCache.getIfPresent(userId);
        if (cached != null) return cached;

        String remoteName = fetchFromUserService(userId);
        if (remoteName != null) {
            nameCache.put(userId, remoteName);
            return remoteName;
        }
        // 降级值不进缓存，避免 user 服务恢复后仍持续返回兜底名。
        return "用户" + userId;
    }

    private String fetchFromUserService(Long userId) {
        try {
            R<UserProfileVO> resp = userServiceClient.getUser(userId);
            if (resp != null && resp.getCode() == 0 && resp.getData() != null) {
                UserProfileVO user = resp.getData();
                if (hasText(user.getNickname())) return user.getNickname();
                if (hasText(user.getUsername())) return user.getUsername();
            }
        } catch (Exception e) {
            // 降级:user 服务不可用时不抛异常,返回兜底名,保证文章正常展示
            log.warn("调用 user 服务失败,降级处理 userId={}, err={}", userId, e.getMessage());
        }
        return null;
    }

    private boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }
}
