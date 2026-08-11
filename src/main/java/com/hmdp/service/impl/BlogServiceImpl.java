package com.hmdp.service.impl;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hmdp.dto.Result;
import com.hmdp.entity.Blog;
import com.hmdp.mapper.BlogMapper;
import com.hmdp.service.IBlogService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.SystemConstants;
import org.springframework.stereotype.Service;

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


    @Override
    public Result queryBlogByUserId(Long userId, Integer current) {
        if(userId==null){
            return Result.fail("用户id不能为空");

        }
        if(current==null||current<1){
            return Result.fail("页码必须大道与0");
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
}
