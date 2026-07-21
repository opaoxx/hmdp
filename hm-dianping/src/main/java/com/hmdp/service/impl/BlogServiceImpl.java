package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hmdp.dto.Result;
import com.hmdp.dto.ScrollResult;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Blog;
import com.hmdp.entity.Follow;
import com.hmdp.entity.User;
import com.hmdp.mapper.BlogMapper;
import com.hmdp.service.IBlogService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.service.IFollowService;
import com.hmdp.service.IUserService;
import com.hmdp.utils.SystemConstants;
import com.hmdp.utils.UserHolder;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static com.hmdp.utils.RedisConstants.BLOG_LIKED_KEY;
import static com.hmdp.utils.RedisConstants.FEED_KEY;

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
    private static final DefaultRedisScript<Long> PUSH_FEED_SCRIPT = new DefaultRedisScript<>(
            "for i, key in ipairs(KEYS) do "
                    + "redis.call('ZADD', key, ARGV[1], ARGV[2]) "
                    + "end "
                    + "return #KEYS",
            Long.class
    );
    @Resource
    private IUserService userService;
    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Resource
    private RedissonClient redissonClient;
    @Resource
    private IFollowService followService;

    @Override
    public Result queryBlogById(Long id) {
        //查询blog
        Blog blog = getById(id);
        if(blog == null){
            return Result.fail("博客不存在");
        }
        //查询blog有关的用户
        queryBlogUser(blog);
        //查询blog是否被点赞
        isBlogLiked(blog);
        return Result.ok(blog);
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
        records.forEach(blog ->{
            queryBlogUser(blog);
            isBlogLiked(blog);
        });
        return Result.ok(records);
    }

    @Override
    public Result likeBlog(Long id) {
        //获取登录用户
        UserDTO user = UserHolder.getUser();
        if (user == null || user.getId() == null) {
            return Result.fail("请先登录");
        }
        Long userId = user.getId();
        RLock lock = redissonClient.getLock("lock:blog:like:" + id + ":" + userId);
        if (!lock.tryLock()) {
            return Result.fail("操作频繁，请稍后重试");
        }
        try {
            //判断用户是否已点赞
            String key = BLOG_LIKED_KEY + id;
            Double score = stringRedisTemplate.opsForZSet().score(key, userId.toString());
            if(score == null){
                //未点赞，点赞数+1，并将用户id存入Redis的ZSet中
                boolean success = update().setSql("liked = liked + 1").eq("id", id).update();
                if(!success){
                    return Result.fail("点赞失败");
                }
                stringRedisTemplate.opsForZSet().add(key, userId.toString(), System.currentTimeMillis());
            }
            else{
                //已点赞，则取消点赞，并将用户从Redis的ZSet中移除
                boolean success = update().setSql("liked = liked - 1").eq("id", id).update();
                if(!success){
                    return Result.fail("取消点赞失败");
                }
                stringRedisTemplate.opsForZSet().remove(key, userId.toString());
            }
            return Result.ok();
        } finally {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    @Override
    public Result queryBlogLikes(Long id) {
        String key = BLOG_LIKED_KEY + id;
        //查询top5的点赞用户
        Set<String> top5 = stringRedisTemplate.opsForZSet().range(key, 0, 4);
        if(top5 == null || top5.isEmpty()){
            return Result.ok(Collections.emptyList());
        }
        //解析用户id
        List<Long> ids = top5.stream().map(Long::valueOf).collect(Collectors.toList());
        String idStr = StrUtil.join(",", ids);
        //根据id查询用户
        List<UserDTO> userDTOS = userService
                .query()
                .in("id", ids)
                .last("ORDER BY FIELD(id," + idStr + ")")
                .list()
                .stream()
                .map(user -> BeanUtil.copyProperties(user, UserDTO.class))
                .collect(Collectors.toList());
        //返回
        return Result.ok(userDTOS);
    }

    @Override
    @Transactional
    public Result saveBlog(Blog blog) {
        // 获取登录用户
        UserDTO user = UserHolder.getUser();
        blog.setUserId(user.getId());
        // 保存探店博文
        boolean success = save(blog);
        if(!success){
            return Result.fail("保存博客失败");
        }
        //查询博客作者的所有粉丝
        List<Follow> follows = followService.query().eq("follow_user_id", user.getId()).list();
        if(follows == null || follows.isEmpty()){
            return Result.ok(blog.getId());
        }
        //推送博客id给所有粉丝
        List<String> feedKeys = follows.stream()
                .map(Follow::getUserId)
                .distinct()
                .map(userId -> FEED_KEY + userId)
                .collect(Collectors.toList());
        Long pushed = stringRedisTemplate.execute(
                PUSH_FEED_SCRIPT,
                feedKeys,
                String.valueOf(System.currentTimeMillis()),
                blog.getId().toString()
        );
        if (pushed == null || pushed != feedKeys.size()) {
            throw new IllegalStateException("博客 Feed 推送失败");
        }
        return Result.ok(blog.getId());
    }

    @Override
    public Result queryBlogOfFollow(Long max, Integer offset) {
        //获取当前用户
        Long userId = UserHolder.getUser().getId();
        String key = FEED_KEY + userId;
        //查询收件箱
        Set<ZSetOperations.TypedTuple<String>> typedTuples = stringRedisTemplate.opsForZSet().reverseRangeByScoreWithScores(key, 0, max, offset, 2);
        //判断是否为空
        if(typedTuples == null || typedTuples.isEmpty()){
            return Result.ok();
        }
        //解析收件箱数据
        List<Long> ids = new ArrayList<>(typedTuples.size());
        long minTime = 0;
        int os = 1;
        for(ZSetOperations.TypedTuple<String> tuple : typedTuples){
            //获取博客id，时间戳
            ids.add(Long.valueOf(tuple.getValue()));
            long time = tuple.getScore().longValue();

            if(time == minTime){
                os++;
            }
            else{
                minTime = time;
                os = 1;
            }
        }
        //根据id查询blog
        String idStr = StrUtil.join(",", ids);
        List<Blog> blogs = query().in("id", ids).last("ORDER BY FIELD(id," + idStr + ")").list();

        for(Blog blog : blogs){
            queryBlogUser(blog);
            isBlogLiked(blog);
        }
        //封装到ScrollResult
        ScrollResult scrollResult = new ScrollResult();
        scrollResult.setList(blogs);
        scrollResult.setMinTime(minTime);
        scrollResult.setOffset(os);
        return Result.ok(scrollResult);
    }

    private void queryBlogUser(Blog blog) {
        Long userId = blog.getUserId();
        User user = userService.getById(userId);
        if (user == null) {
            return;
        }
        blog.setName(user.getNickName());
        blog.setIcon(user.getIcon());
    }

    private void isBlogLiked(Blog blog) {
        //获取登录用户
        UserDTO user = UserHolder.getUser();
        //未登录状态，则无需查询是否点赞
        if(user == null || user.getId() == null){
            blog.setIsLike(false);
            return;
        }
        Long userId = user.getId();
        //判断当前用户是否已经点赞
        String key = BLOG_LIKED_KEY + blog.getId();
        Double score = stringRedisTemplate.opsForZSet().score(key, userId.toString());
        blog.setIsLike(score != null);
    }
}
