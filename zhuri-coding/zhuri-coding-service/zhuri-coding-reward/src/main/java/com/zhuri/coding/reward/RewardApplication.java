package com.zhuri.coding.reward;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;
import org.springframework.cloud.openfeign.EnableFeignClients;

@SpringBootApplication(scanBasePackages = "com.zhuri.coding")
@EnableDiscoveryClient
@EnableFeignClients(basePackages = "com.zhuri.coding.apis")
@MapperScan("com.zhuri.coding.reward.mapper")
public class RewardApplication {

    public static void main(String[] args) {
        SpringApplication.run(RewardApplication.class, args);
    }
}
