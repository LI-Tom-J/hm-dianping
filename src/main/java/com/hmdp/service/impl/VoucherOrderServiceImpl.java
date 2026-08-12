package com.hmdp.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.SimpleRedisLock;
import com.hmdp.utils.UserHolder;
import org.springframework.aop.framework.AopContext;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.time.LocalDateTime;

import static com.hmdp.utils.RedisConstants.LOCK_ORDER_KEY;
import static com.hmdp.utils.RedisConstants.LOCK_ORDER_TTL;

/**
 * 优惠券订单业务实现类
 */
@Service
public class VoucherOrderServiceImpl
        extends ServiceImpl<VoucherOrderMapper, VoucherOrder>
        implements IVoucherOrderService {

    @Resource
    private RedisIdWorker redisIdWorker;

    @Resource
    private ISeckillVoucherService seckillVoucherService;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public Result seckillVoucher(Long voucherId) {
        // 1. 提前拒绝无效参数，避免使用空ID查询数据库。
        if (voucherId == null) {
            return Result.fail("优惠券id不能为空");
        }

        // 2. 一人一单依赖当前用户身份，未登录请求不能进入秒杀流程。
        UserDTO currentUser = UserHolder.getUser();
        if (currentUser == null) {
            return Result.fail("请先登录");
        }

        // 3. 秒杀开始前先校验活动是否存在、时间是否合法以及当前是否还有库存。
        SeckillVoucher seckillVoucher = seckillVoucherService.getById(voucherId);
        if (seckillVoucher == null) {
            return Result.fail("秒杀券不存在");
        }

        LocalDateTime now = LocalDateTime.now();
        if (now.isBefore(seckillVoucher.getBeginTime())) {
            return Result.fail("秒杀尚未开始");
        }
        if (now.isAfter(seckillVoucher.getEndTime())) {
            return Result.fail("秒杀已经结束");
        }
        if (seckillVoucher.getStock() == null || seckillVoucher.getStock() <= 0) {
            return Result.fail("秒杀券库存不足");
        }

        Long userId = currentUser.getId();

        // 4. 所有服务实例竞争同一个用户锁，保证同一用户的下单流程串行执行。
        SimpleRedisLock lock = new SimpleRedisLock(
                LOCK_ORDER_KEY + userId,
                stringRedisTemplate
        );
        boolean lockAcquired = lock.tryLock(LOCK_ORDER_TTL);

        if (!lockAcquired) {
            return Result.fail("请勿重复下单");
        }

        try {
            // 通过 Spring AOP 代理调用，确保 createVoucherOrder() 的事务注解生效。
            IVoucherOrderService proxy =
                    (IVoucherOrderService) AopContext.currentProxy();
            return proxy.createVoucherOrder(voucherId);
        } finally {
            // 无论事务提交还是回滚都释放锁，Lua脚本会校验当前线程是否仍是持有者。
            lock.unlock();
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Result createVoucherOrder(Long voucherId) {
        Long userId = UserHolder.getUser().getId();

        // 1. 重复检查和订单写入必须处在同一把锁、同一个事务边界中。
        int orderCount = lambdaQuery()
                .eq(VoucherOrder::getUserId, userId)
                .eq(VoucherOrder::getVoucherId, voucherId)
                .count();

        if (orderCount > 0) {
            return Result.fail("不能重复购买同一张优惠券");
        }

        /*
         * 2. 把 stock > 0 放入更新条件，让库存判断和扣减由一条SQL原子完成，
         * 避免多个用户并发请求造成库存超卖。
         */
        boolean stockUpdated = seckillVoucherService.lambdaUpdate()
                .setSql("stock = stock - 1")
                .eq(SeckillVoucher::getVoucherId, voucherId)
                .gt(SeckillVoucher::getStock, 0)
                .update();

        if (!stockUpdated) {
            return Result.fail("秒杀券库存不足");
        }

        // 3. Redis时间戳和自增序列共同生成全局唯一订单ID。
        long orderId = redisIdWorker.nextId("order");

        VoucherOrder voucherOrder = new VoucherOrder();
        voucherOrder.setId(orderId);
        voucherOrder.setUserId(userId);
        voucherOrder.setVoucherId(voucherId);

        /*
         * 4. 保存失败必须抛出异常，Spring 才会回滚前面的库存扣减，
         * 避免出现库存已经减少但订单没有生成的不一致状态。
         */
        boolean orderSaved = save(voucherOrder);
        if (!orderSaved) {
            throw new IllegalStateException("秒杀订单创建失败");
        }

        return Result.ok(orderId);
    }
}
