package com.hmdp.config;

import com.hmdp.interceptor.LoginInterceptor;
import com.hmdp.interceptor.RefreshTokenInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import javax.annotation.Resource;

/**
 * @author zt
 * @version 1.0
 */
@Configuration
public class MvcConfig implements WebMvcConfigurer {

    @Resource
    private LoginInterceptor loginInterceptor;

    @Resource
    private RefreshTokenInterceptor refreshTokenInterceptor;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        //登录拦截器 排除举例的这些路径 .order() 多个拦截器可以安排执行顺序，括号里数字越小，执行顺序越靠前
        registry.addInterceptor(loginInterceptor)
                .excludePathPatterns(
                        "/user/login",
                        "/voucher/**",
                        "/user/code",
                        "/shop/**",
                        "/metrics/**",
                        "/shop-type/**",
                        "/admin/**",
                        "/dashboard.html",
                        "/actuator/**").order(1);
        //token刷新的拦截器
        registry.addInterceptor(refreshTokenInterceptor)
                .addPathPatterns("/**").order(0);
    }
}
