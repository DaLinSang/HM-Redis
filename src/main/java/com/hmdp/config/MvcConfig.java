package com.hmdp.config;

import com.hmdp.utils.LoginInterceptor;
import com.hmdp.utils.RefrechTokenInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import javax.annotation.Resource;

@Configuration
public class MvcConfig implements WebMvcConfigurer {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        //登录用的拦截器
        registry.addInterceptor(new LoginInterceptor())
                .excludePathPatterns(
                "/user/code",
                "/user/login",
                "/shop/**",
                "/shop-type/**",
                "/voucher/**",
                "/blog/hot",
                "/upload/**"
        ).order(1);
        //token刷新的拦截器
        registry.addInterceptor(new RefrechTokenInterceptor(stringRedisTemplate)).addPathPatterns("/**").order(0);
    }
}
