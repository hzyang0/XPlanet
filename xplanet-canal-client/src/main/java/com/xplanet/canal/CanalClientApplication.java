package com.xplanet.canal;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.ComponentScan;

@SpringBootApplication
@ComponentScan(basePackages = {"com.xplanet.canal", "com.xplanet.common"})
public class CanalClientApplication {
    public static void main(String[] args) {
        SpringApplication.run(CanalClientApplication.class, args);
    }
}
