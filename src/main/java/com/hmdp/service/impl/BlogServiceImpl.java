package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hmdp.dto.Result;
import com.hmdp.dto.ScrollResult;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Blog;
import com.hmdp.entity.Follow;
import com.hmdp.entity.User;
import com.hmdp.entity.UserInfo;
import com.hmdp.mapper.BlogMapper;
import com.hmdp.service.IBlogService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.service.IFollowService;
import com.hmdp.service.IUserService;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.SystemConstants;
import com.hmdp.utils.UserHolder;
import jodd.util.StringUtil;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.nio.file.NotLinkException;
import java.util.ArrayList;
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
public class BlogServiceImpl extends ServiceImpl<BlogMapper, Blog> implements IBlogService {
    @Resource
    private IUserService userService;
    @Resource
    private IFollowService followService;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public Result queryBlogById(Long id) {
        Blog blog = getById(id);
        if (blog==null){
            return Result.fail("此笔记不存在！");
        }
        queryUserByBlog(blog);
        queryLiked(blog);
        return Result.ok(blog);
    }

    private void queryLiked(Blog blog) {
        if(UserHolder.getUser()==null){
            return;
        }
        Long id = blog.getId();
        Long userId = UserHolder.getUser().getId();
        String key = RedisConstants.BLOG_LIKED_KEY + id;
        Double score = stringRedisTemplate.opsForZSet().score(key, userId.toString());
        blog.setIsLike(score!=null);
    }

    @Override
    public Result queryHotBlog(Integer current) {
        // 根据用户查询
        Page<Blog> page = query()
                .orderByDesc("liked")
                .page(new Page<>(current, SystemConstants.MAX_PAGE_SIZE));
        // 获取当前页数据
        List<Blog> records = page.getRecords();
        // 查询用户
        records.forEach(blog -> {
            queryUserByBlog(blog);
            queryLiked(blog);
        });
        return Result.ok(records);
    }

    @Override
    public Result likeBlog(Long id) {
        // 给指定blog点赞，一个用户只点赞一次
        // 1. 判断redis中该blog是否有该用户的点赞
        Long userId = UserHolder.getUser().getId();
        String key = RedisConstants.BLOG_LIKED_KEY + id;
        Double score = stringRedisTemplate.opsForZSet().score(key, userId.toString());
        if(score==null){ //没有点赞过
            // 数据库 liked+1
            boolean isSuccess = this.update().setSql("liked = liked+1").eq("id", id).update();
            if(isSuccess){
                //保存到redis  score为当前时间戳
                stringRedisTemplate.opsForZSet().add(key, String.valueOf(userId),System.currentTimeMillis());
            }
        }else{ //已经点赞过
            // 数据库 liked-1
            boolean isSuccess = this.update().setSql("liked = liked-1").eq("id", id).update();
            if(isSuccess){
                // 从redis中移除
                stringRedisTemplate.opsForZSet().remove(key, userId.toString());
            }
        }
        return Result.ok();
    }

    @Override
    public Result queryBlogLikes(Long id) {
        // 根据博客id 查询top5的用户点赞信息
        // 从redis中获得用户id
        // ZRANGE key 0 4
        String key = RedisConstants.BLOG_LIKED_KEY + id;
        Set<String> set = stringRedisTemplate.opsForZSet().range(key, 0, 4);
        // 没有点赞
        if (set == null || set.isEmpty()) {
            return Result.ok(Collections.emptyList());
        }
        List<Long> userIds = set.stream().map(userId -> Long.valueOf(userId)).collect(Collectors.toList());
        String idStr = StrUtil.join(",",userIds);
        // WHERE id In (5,1)  ORDER BY FIELD(id,5,1)
        List<UserDTO> res = userService.query().in("id", userIds).last("ORDER BY FIELD(id," + idStr + ")").list().stream().map(
                user -> {
                    UserDTO userDTO = new UserDTO();
                    BeanUtil.copyProperties(user, userDTO);
                    return userDTO;
                }
        ).collect(Collectors.toList());
        return Result.ok(res);
    }

    @Override
    public Result saveBlog(Blog blog) {
        // 获取登录用户
        UserDTO user = UserHolder.getUser();
        blog.setUserId(user.getId());
        // 保存探店博文
        boolean isSuccess = save(blog);
        if (!isSuccess){
            return Result.fail("保存笔记失败！");
        }else{
            // 获取该用户的所有粉丝
            LambdaQueryWrapper<Follow> queryWrapper = new LambdaQueryWrapper<>();
            queryWrapper.eq(Follow::getUserId, user.getId());
            queryWrapper.select(Follow::getFollowUserId);
            // 将笔记id推送至其粉丝的收件箱
            followService.list(queryWrapper).stream().forEach(follow -> {
                Long followId = follow.getFollowUserId();
                String key = RedisConstants.FEED_KEY + followId;
                stringRedisTemplate.opsForZSet().add(key, blog.getId().toString(), System.currentTimeMillis());
            });
        }
        // 返回id
        return Result.ok(blog.getId());
    }

    @Override
    public Result queryBlogOfFollow(Long max, Integer offset) {
        // 实现滚动分页查询
        // 获得用户id
        Long userId = UserHolder.getUser().getId();
        // 获得收件箱的key
        String key = RedisConstants.FEED_KEY + userId;
        Set<ZSetOperations.TypedTuple<String>> typedTuples = stringRedisTemplate.opsForZSet().reverseRangeByScoreWithScores(key, 0, max, offset, 2);//2表示一次获取2个blog
        if(typedTuples==null || typedTuples.isEmpty()){
            return Result.ok();
        }
        long maxTime=0L; //数据最后一次的score 作为下一次查询的max
        int os=1; // 最后一条数据出现的次数,最为下一次查询的offset
        List<Long> blogIds = new ArrayList<>(typedTuples.size());
        // 从集合中获取：blogId,maxTime offset
        for (ZSetOperations.TypedTuple<String> tuple : typedTuples) {
            blogIds.add(Long.valueOf(tuple.getValue()));
            long score = tuple.getScore().longValue();
            if(score==maxTime){
                os++;
            }else{
                maxTime=score;
                os=1; //重新计数
            }
        }
        // 根据blogIds查询所有blog数据
        String idStr = StrUtil.join(",",blogIds);
        List<Blog> blogs = query().in("id", blogIds).last("ORDER BY FIELD(id," + idStr + ")").list();
        blogs.stream().forEach(blog -> {
            queryUserByBlog(blog);  //查询发博客人的一些基本信息
            queryLiked(blog); // 查询该条笔记用户是否被点赞
        });
        ScrollResult res = new ScrollResult();
        res.setList(blogs);
        res.setMinTime(maxTime);
        res.setOffset(os);
        return Result.ok(res);
    }

    private void queryUserByBlog(Blog blog){
        Long userId = blog.getUserId();
        User user = userService.getById(userId);
        blog.setName(user.getNickName());
        blog.setIcon(user.getIcon());
    }
}
