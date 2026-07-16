package com.xplanet.interaction;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
@EnableFeignClients(basePackages = "com.xplanet.interaction.client")
@ComponentScan(basePackages = {"com.xplanet.interaction", "com.xplanet.common"})
@MapperScan("com.xplanet.interaction.persistence")
public class InteractionApplication {
    public static void main(String[] args) {
        SpringApplication.run(InteractionApplication.class, args);
    }
}
