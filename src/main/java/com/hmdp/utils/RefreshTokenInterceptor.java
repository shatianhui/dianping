package com.hmdp.utils;

import cn.hutool.core.bean.BeanUtil;
import com.hmdp.dto.UserDTO;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;

import java.util.Map;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.LOGIN_USER_KEY;
import static com.hmdp.utils.RedisConstants.LOGIN_USER_TTL;
import static net.sf.jsqlparser.util.validation.metadata.NamedObject.user;

/**
 * description: 登录检查拦截器<br>
 * date: 2022/9/7 20:08 <br>
 * author: shatianhui <br>
 * version: 1.0 <br>
 */
@Component
public class RefreshTokenInterceptor implements HandlerInterceptor {
    @Autowired
    private StringRedisTemplate stringRedisTemplate;
    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        // 从请求头中获取token
        String token=request.getHeader("authorization");
        // 判断是否可以在redis中获得user
        String key = LOGIN_USER_KEY + token;
        Map<Object, Object> userMap = stringRedisTemplate.opsForHash().entries(key);
        if (! userMap.isEmpty()){
            // 将用户信息保存到ThreadLocal，便于其他Controller获取使用
            // 将userMap转成对象
            UserDTO user = BeanUtil.mapToBean(userMap, UserDTO.class, false);
            UserHolder.saveUser(user);
            // 刷新用户信息的有效时间
            stringRedisTemplate.expire(key, LOGIN_USER_TTL, TimeUnit.MINUTES);
        }
        return true; //放行
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) throws Exception {
        UserHolder.removeUser();
    }
}
