package com.hmdp.config;

import com.hmdp.utils.LoginCheckInterceptor;
import com.hmdp.utils.RefreshTokenInterceptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * description: SpringMvcConfig <br>
 * date: 2022/9/7 20:30 <br>
 * author: shatianhui <br>
 * version: 1.0 <br>
 */

@Configuration
public class SpringMvcConfig implements WebMvcConfigurer {
    @Autowired
    private LoginCheckInterceptor loginCheckInterceptor;
    @Autowired
    private RefreshTokenInterceptor refreshTokenInterceptor;
    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(refreshTokenInterceptor).addPathPatterns("/**"); //拦截所有请求
        registry.addInterceptor(loginCheckInterceptor).excludePathPatterns(
                "/user/login",
                "/user/code",
                "/shop/*",
                "/blog/*",
                "/shop-type/*",
                "/voucher/*");
    }
}
