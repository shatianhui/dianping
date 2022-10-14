package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.BooleanUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Follow;
import com.hmdp.mapper.FollowMapper;
import com.hmdp.service.IFollowService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.service.IUserService;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.UserHolder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.text.FieldPosition;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class FollowServiceImpl extends ServiceImpl<FollowMapper, Follow> implements IFollowService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private IUserService userService;

    @Override
    public Result follow(Long followId, Boolean isFollow) {
        // 实现关注和取关功能
        if(UserHolder.getUser()==null){
            return Result.fail("你尚未登录，无法关注");
        }
        Long userId = UserHolder.getUser().getId();
        String key = RedisConstants.FOLLOW_KEY + userId;
        if(BooleanUtil.isTrue(isFollow)){ //实现关注
            Follow follow=new Follow();
            follow.setUserId(userId);
            follow.setFollowUserId(followId);
            boolean isSuccess = save(follow);
            if(isSuccess){
                // 关注成功 ，需要将followId 放到Redis set集合中
                stringRedisTemplate.opsForSet().add(key, followId.toString());
                return Result.ok();
            }
        }else{  //取关
            LambdaQueryWrapper<Follow> lambdaQueryWrapper = new LambdaQueryWrapper<>();
            lambdaQueryWrapper.eq(Follow::getUserId, userId);
            lambdaQueryWrapper.eq(Follow::getFollowUserId, followId);
            boolean isSuccess = remove(lambdaQueryWrapper);
            if (isSuccess){
                // 取关成功，需要将followId 移除Redis set集合
                stringRedisTemplate.opsForSet().remove(key, followId.toString());
                return Result.ok();
            }
        }
        return Result.fail("未知错误！");
    }

    @Override
    public Result isFollow(Long followId) {
        //判断是否关注
        if(UserHolder.getUser()==null){
            return Result.ok(false);
        }
        Long userId = UserHolder.getUser().getId();
        LambdaQueryWrapper<Follow> lambdaQueryWrapper = new LambdaQueryWrapper<>();
        lambdaQueryWrapper.eq(Follow::getUserId, userId);
        lambdaQueryWrapper.eq(Follow::getFollowUserId, followId);
        int count = count(lambdaQueryWrapper);
        return Result.ok(count>0);
    }

    @Override
    public Result commonFollow(Long id) {
        Long userId = UserHolder.getUser().getId();
        String key1 = RedisConstants.FOLLOW_KEY + id;
        String key2 = RedisConstants.FOLLOW_KEY + userId;
        Set<String> set = stringRedisTemplate.opsForSet().intersect(key1, key2);
        if(set==null || set.isEmpty()){ //没有共同关注
            return Result.ok(Collections.emptyList());
        }
        List<Long> ids = set.stream().map(ID -> Long.valueOf(ID)).collect(Collectors.toList());
        List<UserDTO> res = userService.listByIds(ids).stream().map(user -> {
            UserDTO userDTO = new UserDTO();
            BeanUtil.copyProperties(user, userDTO);
            return userDTO;
        }).collect(Collectors.toList());
        return Result.ok(res);
    }
}
