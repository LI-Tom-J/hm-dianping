package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Follow;
import com.hmdp.mapper.FollowMapper;
import com.hmdp.service.IFollowService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.service.IUserService;
import com.hmdp.utils.UserHolder;
import org.springframework.beans.BeanUtils;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;

import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static com.hmdp.utils.RedisConstants.FOLLOW_KEY;

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
    public Result follow(Long followUserId, Boolean isFollow) {
       if(followUserId==null){
           return Result.fail("被关注用户id不能为空");
       }
       if(isFollow==null){
           return Result.fail("关注状态不能为空");
       }
       UserDTO currentUser = UserHolder.getUser();
       if(currentUser==null){
           return Result.fail("请先登录");
       }
       Long userId=currentUser.getId();
       String key=FOLLOW_KEY+userId;

       if(userId.equals(followUserId)){
           return Result.fail("不能关注自己");
       }
       if(Boolean.TRUE.equals(isFollow)){
           int followedCount = lambdaQuery()
                   .eq(Follow::getUserId,userId)
                   .eq(Follow::getFollowUserId,followUserId)
                   .count();
           if(followedCount>0){
               /*
                * 数据库已经存在关系时仍执行SADD。
                * SADD本身具有幂等性，可以顺便修复Redis缺失的关注关系。
                */
               stringRedisTemplate.opsForSet()
                       .add(key,followUserId.toString());

               return Result.ok();
           }
           Follow follow = new Follow();
           follow.setUserId(userId);
           follow.setFollowUserId(followUserId);

           boolean saved=save(follow);
           if(!saved){
               return Result.fail("关注失败");
           }
           stringRedisTemplate.opsForSet()
                   .add(key,followUserId.toString());

       }else {
           lambdaUpdate()
                   .eq(Follow::getUserId,userId)
                   .eq(Follow::getFollowUserId,followUserId)
                   .remove();

           stringRedisTemplate.opsForSet()
                   .remove(key, followUserId.toString());
       }
        return Result.ok();

    }

    @Override
    public Result isFollow(Long followUserId) {
        if(followUserId==null){
            return Result.fail("被关注用户id不能为空");
        }
        UserDTO currentUser = UserHolder.getUser();
        if(currentUser==null){
            return Result.fail("请先登录");
        }

        int followedCount=lambdaQuery()
                .eq(Follow::getUserId,currentUser.getId())
                .eq(Follow::getFollowUserId,followUserId)
                .count();

        return Result.ok(followedCount>0);
    }

    @Override
    public Result followCommons(Long otherUserId) {
        if(otherUserId==null){
            return Result.fail("用户id不能为空");
        }
        UserDTO currentUser = UserHolder.getUser();
        if(currentUser==null){
            return Result.fail("请先登录");
        }
        String currentUserKey=FOLLOW_KEY+currentUser.getId();
        String otherUserKey=FOLLOW_KEY+otherUserId;

        Set<String> commonUserIds=stringRedisTemplate.opsForSet()
                .intersect(currentUserKey,otherUserKey);

        if(commonUserIds==null||commonUserIds.isEmpty()){
            return Result.ok(Collections.emptyList());
        }

        List<Long> userIds=commonUserIds.stream()
                .map(Long::valueOf)
                .collect(Collectors.toList());

        List<UserDTO> commonUsers=userService.listByIds(userIds)
                .stream()
                .map(user-> BeanUtil.copyProperties(user,UserDTO.class))
                .collect(Collectors.toList());

        return Result.ok(commonUsers);
    }
}
