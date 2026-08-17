package com.hmdp.controller;


import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Blog;
import com.hmdp.entity.User;
import com.hmdp.service.IBlogService;
import com.hmdp.service.IUserService;
import com.hmdp.utils.SystemConstants;
import com.hmdp.utils.UserHolder;
import org.springframework.web.bind.annotation.*;

import javax.annotation.Resource;
import java.util.List;


@RestController
@RequestMapping("/blog")
public class BlogController {

    @Resource
    private IBlogService blogService;
    @Resource
    private IUserService userService;

    @PostMapping
    public Result saveBlog(@RequestBody Blog blog) {
        // 发布和推送属于业务流程，统一交给Service处理
        return blogService.saveBlog(blog);
    }
    /**
     * 点赞或取消点赞。
     */
    @PutMapping("/like/{id}")
    public Result likeBlog(@PathVariable("id") Long blogId) {
        return blogService.likeBlog(blogId);
    }

    @GetMapping("/of/me")
    public Result queryMyBlog(@RequestParam(value = "current", defaultValue = "1") Integer current) {
        // 获取登录用户
        UserDTO user = UserHolder.getUser();
        // 根据用户查询
        Page<Blog> page = blogService.query()
                .eq("user_id", user.getId()).page(new Page<>(current, SystemConstants.MAX_PAGE_SIZE));
        // 获取当前页数据
        List<Blog> records = page.getRecords();
        return Result.ok(records);
    }

    /**
     * 分页查询热门探店笔记。
     */
    @GetMapping("/hot")
    public Result queryHotBlog(
            @RequestParam(value = "current", defaultValue = "1")
            Integer current) {

        return blogService.queryHotBlog(current);
    }

    @GetMapping("/of/user")
    public Result queryBlogByUserId(
            @RequestParam("id") Long userId,
            @RequestParam(value = "current", defaultValue = "1") Integer current) {

        return blogService.queryBlogByUserId(userId, current);
    }

    /**
     * 根据博客ID查询详情
     */
    @GetMapping("/{id}")
    public Result queryBlogById(@PathVariable("id") Long blogId) {
        return blogService.queryBlogById(blogId);
    }

    @GetMapping("/likes/{id}")
    public Result queryBlogLikes(@PathVariable("id") Long blogId) {
        return blogService.queryBlogLikes(blogId);
    }

}
