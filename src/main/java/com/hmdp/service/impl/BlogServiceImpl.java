package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hmdp.dto.FeedQueryDTO;
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
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Service;

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

    @Resource
    private IUserService userService;

    @Resource
    private IFollowService followService;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public Result queryBlogByUserId(Long userId, Integer current) {
        if(userId==null){
            return Result.fail("用户id不能为空");

        }
        if(current==null||current<1){
            return Result.fail("页码必须大于0");
        }
        Page<Blog> blogPage=lambdaQuery()
                .eq(Blog::getUserId,userId)
                .orderByDesc(Blog::getCreateTime)
                .page(new Page<>(
                        current,
                        SystemConstants.MAX_PAGE_SIZE
                ));
    return Result.ok(blogPage.getRecords());
    }

    /**
     * 查询博客详情，并补充作者信息和当前用户点赞状态。
     */
    @Override
    public Result queryBlogById(Long blogId) {
        if (blogId == null) {
            return Result.fail("博客id不能为空");
        }

        Blog blog = getById(blogId);
        if (blog == null) {
            return Result.fail("博客不存在");
        }

        fillBlogAuthor(blog);
        fillBlogLikeStatus(blog);

        return Result.ok(blog);
    }

    @Override
    public Result queryHotBlog(Integer current) {
        if(current==null||current<1){
            return Result.fail("页码必须大于0");
        }
        Page<Blog> page=lambdaQuery()
                .orderByDesc(Blog::getLiked)
                .page(new Page<>(
                        current,
                        SystemConstants.MAX_PAGE_SIZE
                ));
        List<Blog> records=page.getRecords();

        records.forEach(blog->{
            fillBlogAuthor(blog);
            fillBlogLikeStatus(blog);
        });

        return Result.ok(records);
    }

    /**
     * Blog表只保存作者ID，需要从用户表补充页面展示所需的昵称和头像。
     */
    private void fillBlogAuthor(Blog blog) {
        User author = userService.getById(blog.getUserId());

        if (author == null) {
            return;
        }

        blog.setName(author.getNickName());
        blog.setIcon(author.getIcon());
    }
    /**
     * 根据Redis ZSet中是否存在当前用户，设置博客点赞状态。
     */
    private void fillBlogLikeStatus(Blog blog) {
        UserDTO currentUser = UserHolder.getUser();

        /*
         * 热门列表和博客详情允许未登录访问，
         * 未登录用户没有点赞状态。
         */
        if (currentUser == null) {
            blog.setIsLike(false);
            return;
        }

        String key = BLOG_LIKED_KEY + blog.getId();

        Double score = stringRedisTemplate.opsForZSet().score(
                key,
                currentUser.getId().toString()
        );

        blog.setIsLike(score != null);
    }

    @Override
    public Result likeBlog(Long blogId) {
        if(blogId==null){
            return Result.fail("博客id不能为空");
        }
    UserDTO currentUser = UserHolder.getUser();
        if (currentUser == null) {
            return Result.fail("请先登录");
        }

        String userId = currentUser.getId().toString();
        String key = BLOG_LIKED_KEY + blogId;


        Double score = stringRedisTemplate.opsForZSet().score(key,userId);
        if(score==null){
            boolean updated=lambdaUpdate()
                    .setSql("liked=liked+1")
                    .eq(Blog::getId,blogId)
                    .update();

            if(!updated){
                return Result.fail("博客不存在或点赞失败");
            }
            stringRedisTemplate.opsForZSet().add(
                    key,userId,System.currentTimeMillis()
            );
        }else {
            boolean updated=lambdaUpdate()
                    .setSql("liked=liked-1")
                    .eq(Blog::getId,blogId)
                    .gt(Blog::getLiked,0)
                    .update();
            if(!updated){
                return Result.fail("博客不存在或取消点赞失败");
            }
            stringRedisTemplate.opsForZSet().remove(key, userId);
        }

    return Result.ok();

    }

    @Override
    public Result queryBlogLikes(Long blogId) {
        if(blogId==null){
            return Result.fail("博客id不能为空");
        }
        String key = BLOG_LIKED_KEY + blogId;

        Set<String> top5UserIds=stringRedisTemplate.opsForZSet()
                .range(key,0,4);
        if(top5UserIds==null||top5UserIds.isEmpty()){
            return  Result.ok(Collections.emptyList());
        }

        List<Long> userIds=top5UserIds.stream()
                .map(Long::valueOf)
                .collect(Collectors.toList());

        String userIdStr = StrUtil.join(",",userIds);

        List<UserDTO> users=userService.query()
                .in("id",userIds)
                .last(("order by field(id,"+userIdStr+")"))
                .list()
                .stream()
                .map(user -> BeanUtil.copyProperties(user, UserDTO.class))
                .collect(Collectors.toList());


        return Result.ok(users);
    }

    @Override
    public Result saveBlog(Blog blog) {
        UserDTO currentUser = UserHolder.getUser();
        if (currentUser == null) {
            return Result.fail("请先登录");
        }

        blog.setUserId(currentUser.getId());

        boolean saved=save(blog);
        if(!saved){
            return Result.fail("发布笔记失败");
        }

        List<Follow> followers = followService.lambdaQuery()
                .eq(Follow::getFollowUserId,currentUser.getId())
                .list();

        long publishTime = System.currentTimeMillis();

        for (Follow follow : followers) {
            Long followerId=follow.getUserId();
            String feedKey=FEED_KEY+followerId;

            stringRedisTemplate.opsForZSet().add(
                    feedKey,
                    blog.getId().toString(),
                    publishTime
            );
        }

        return Result.ok(blog.getId());
    }


    @Override
    public Result queryBlogOfFollow(FeedQueryDTO query) {
        if(query==null||query.getLastId()==null||query.getLastId()<=0){
            return Result.fail("时间游标不能为空");
        }

        Long max = query.getLastId();
        Integer offset = query.getOffset();

        if(offset==null||offset<0){
            return Result.fail("偏移量不能小于0");
        }

        UserDTO currentUser = UserHolder.getUser();
        if (currentUser == null) {
            return Result.fail("请先登录");
        }
        String feedKey = FEED_KEY + currentUser.getId();

        Set<ZSetOperations.TypedTuple<String>> typedTuples=
                stringRedisTemplate.opsForZSet()
                        .reverseRangeByScoreWithScores(
                                feedKey,
                                0,
                                max,
                                offset,
                                SystemConstants.DEFAULT_PAGE_SIZE
                        );
        if(typedTuples==null||typedTuples.isEmpty()){
            return Result.ok();
        }

        List<Long> blogIds=new ArrayList<>(typedTuples.size());
        long minTime = 0L;
        int nextOffset = 0;

        for (ZSetOperations.TypedTuple<String> tuple : typedTuples) {
            String blogId = tuple.getValue();
            Double score=tuple.getScore();

            if(blogId==null||score==null){
                continue;
            }
            blogIds.add(Long.valueOf(blogId));

            long publishTime = score.longValue();
            if(publishTime==minTime){
                nextOffset++;
            }else {
                minTime = publishTime;
                nextOffset=1;
            }

        }
        if(minTime==max){
            nextOffset+=offset;
        }
        if(blogIds.isEmpty()){
            return Result.ok();
        }

        String blogIdStr = StrUtil.join(",",blogIds);

        List<Blog> blogs=lambdaQuery()
                .in(Blog::getId,blogIds)
                .last("order by field(id,"+blogIdStr+")")
                .list();

        blogs.forEach(blog -> {
            fillBlogAuthor(blog);
            fillBlogLikeStatus(blog);
        });

        ScrollResult scrollResult = new ScrollResult();
        scrollResult.setList(blogs);
        scrollResult.setMinTime(minTime);
        scrollResult.setOffset(nextOffset);

        return Result.ok(scrollResult);
    }
}
