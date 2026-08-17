package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Blog;
import com.hmdp.entity.User;
import com.hmdp.mapper.BlogMapper;
import com.hmdp.service.IBlogService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.service.IUserService;
import com.hmdp.utils.SystemConstants;
import com.hmdp.utils.UserHolder;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static com.hmdp.utils.RedisConstants.BLOG_LIKED_KEY;

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
}
