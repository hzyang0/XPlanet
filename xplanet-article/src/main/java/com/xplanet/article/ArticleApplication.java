package com.xplanet.article;

import com.xplanet.article.client.UserServiceClient;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
@EnableFeignClients(basePackageClasses = UserServiceClient.class)
@ComponentScan(basePackages = {"com.xplanet.article", "com.xplanet.common"})
@MapperScan({"com.xplanet.article.mapper", "com.xplanet.article.comment",
        "com.xplanet.article.projection", "com.xplanet.article.outbox"})
public class ArticleApplication {
    public static void main(String[] args) {
        SpringApplication.run(ArticleApplication.class, args);
    }
}
