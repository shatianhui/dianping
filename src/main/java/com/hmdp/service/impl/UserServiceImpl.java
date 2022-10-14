package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.bean.copier.CopyOptions;
import cn.hutool.core.lang.UUID;
import cn.hutool.core.util.RandomUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.LoginFormDTO;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.User;
import com.hmdp.mapper.UserMapper;
import com.hmdp.service.IUserService;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.RegexUtils;
import com.hmdp.utils.SystemConstants;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.connection.BitFieldSubCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.servlet.http.HttpSession;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.*;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
@Slf4j
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements IUserService {
    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public Result sendCode(String phone, HttpSession session) {
        // 校验手机号
        if (RegexUtils.isPhoneInvalid(phone)) {
            return Result.fail("输入的手机号非法！");
        }
        // 生成验证码
        String code = RandomUtil.randomNumbers(6);
        // 将验证码保存到redis  key为手机号  设置验证码的有效时长为2min
        stringRedisTemplate.opsForValue().set(LOGIN_CODE_KEY+phone,code,LOGIN_CODE_TTL, TimeUnit.MINUTES);
        // 发送验证码
        log.info("验证码发送成功！验证码为：{}",code);
        return Result.ok();
    }

    @Override
    public Result login(LoginFormDTO loginForm, HttpSession session) {
        String phone = loginForm.getPhone();
        String code = loginForm.getCode();
        // 校验验证码  从redis中获取
        if (code==null||!stringRedisTemplate.opsForValue().get(LOGIN_CODE_KEY+phone).equals(code)){
            return Result.fail("验证码输入错误!");
        }
        // 根据手机号查询用户
        LambdaQueryWrapper<User> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(User::getPhone, phone);
        User user = this.getOne(queryWrapper);
        if (user==null){
            user = createNewUser(phone);
        }
        // 保存用户的关键信息到redis
        UserDTO userDTO=BeanUtil.copyProperties(user, UserDTO.class);
        // 将对象转换为hashMap （注意，要比属性的其他类型都转成string）
        Map<String, Object> userMap = BeanUtil.beanToMap(userDTO, new HashMap<>(), CopyOptions.create().setIgnoreNullValue(true).
                setFieldValueEditor((filed, value) -> value.toString()));
        // 创建token作为key
        String token = UUID.randomUUID().toString();
        String key = LOGIN_USER_KEY + token;
        stringRedisTemplate.opsForHash().putAll(key,userMap);
        // 设置有效时间
        stringRedisTemplate.expire(key, LOGIN_USER_TTL, TimeUnit.MINUTES);
        // 将token返回
        return Result.ok(token);
    }

    @Override
    public Result sign() {
        // 获取用户id
        Long userId = UserHolder.getUser().getId();
        // 获取当前时间
        LocalDateTime now = LocalDateTime.now();
        String key = USER_SIGN_KEY + userId + now.format(DateTimeFormatter.ofPattern(":YYYYMM"));
        // 获取今天是本月的第几天
        int dayOfMonth = now.getDayOfMonth();
        stringRedisTemplate.opsForValue().setBit(key, dayOfMonth-1, true); //要减一，下标是从0开始的
        return Result.ok();
    }

    @Override
    public Result signCount() {
        // 获取用户id
        Long userId = UserHolder.getUser().getId();
        // 获取当前时间
        LocalDateTime now = LocalDateTime.now();
        String key = USER_SIGN_KEY + userId + now.format(DateTimeFormatter.ofPattern(":YYYYMM"));
        // 获取今天是本月的第几天
        int dayOfMonth = now.getDayOfMonth();
        // 获取该月的数据
        // BITFILED key GET udayOFMonth 0
        List<Long> res = stringRedisTemplate.opsForValue().bitField(key,
                BitFieldSubCommands.create().get(BitFieldSubCommands.BitFieldType.unsigned(dayOfMonth)).valueAt(0));
        if(res==null || res.size()==0){
            return Result.ok(0);
        }
        Long data = res.get(0);
        int count=0;
        while(true){
            if((data & 1)==0){
                break;
            }
            data =data>>1;
            count++;
        }
        return Result.ok(count);
    }

    private User createNewUser(String phone) {
        User user = new User();
        user.setPhone(phone);
        String nickName = SystemConstants.USER_NICK_NAME_PREFIX + RandomUtil.randomString(10);
        user.setNickName(nickName);
        save(user);
        return user;
    }
}
