package com.xplanet.article.client;

import com.xplanet.api.vo.UserProfileVO;
import com.xplanet.common.response.R;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

/**
 * user 服务的声明式 HTTP 契约。
 *
 * <p>这里只承载短耗时同步查询。连接/读取超时由 application.yml 统一配置。</p>
 */
@FeignClient(name = "user-service", url = "${user-service.base-url:http://localhost:8083}")
public interface UserServiceClient {

    @GetMapping("/api/user/{id}")
    R<UserProfileVO> getUser(@PathVariable("id") Long id);
}
