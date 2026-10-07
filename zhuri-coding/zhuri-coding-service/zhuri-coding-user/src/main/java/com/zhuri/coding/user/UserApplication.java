package com.zhuri.coding.user;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.context.annotation.Bean;


@SpringBootApplication(scanBasePackages = "com.zhuri.coding")
@EnableDiscoveryClient
@EnableFeignClients(basePackages = "com.zhuri.coding.apis")
@MapperScan("com.zhuri.coding.user.mapper")
public class UserApplication {

    public static void main(String[] args) {
        SpringApplication.run(UserApplication.class,args);
    }

    /**
     * 分页插件。
     *
     * <p>本模块此前**漏挂**了这个插件，而 {@code ApUserServiceImpl#searchUser} 与运营侧的
     * {@code UserBanService#page} 都在传 {@code Page} 参数 —— 没有插件时 MyBatis-Plus
     * 既不拼 {@code LIMIT} 也不查总数，页码被静默忽略、一次把全表捞出来。这类缺陷不报错，
     * 只表现为"翻页没反应、响应越来越慢"。content 与 notification 两个服务早已注册，user 是漏的那个。
     */
    @Bean
    public MybatisPlusInterceptor mybatisPlusInterceptor() {
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
        interceptor.addInnerInterceptor(new PaginationInnerInterceptor(DbType.MYSQL));
        return interceptor;
    }
}
