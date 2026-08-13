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
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.aop.framework.AopContext;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import javax.annotation.Resource;
import java.util.Arrays;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static com.hmdp.utils.RedisConstants.LOCK_ORDER_KEY;
import static com.hmdp.utils.RedisConstants.SECKILL_ORDER_KEY;
import static com.hmdp.utils.RedisConstants.SECKILL_STOCK_KEY;

/**
 * 优惠券订单业务实现类。
 */
@Slf4j
@Service
public class VoucherOrderServiceImpl
        extends ServiceImpl<VoucherOrderMapper, VoucherOrder>
        implements IVoucherOrderService {

    private static final long SCRIPT_SUCCESS = 0L;
    private static final long SCRIPT_STOCK_NOT_ENOUGH = 1L;
    private static final long SCRIPT_DUPLICATE_ORDER = 2L;
    private static final long SCRIPT_STOCK_NOT_INITIALIZED = 3L;
    private static final int ORDER_QUEUE_CAPACITY = 1024;

    /**
     * 脚本对象全类共享，避免每次秒杀请求重新读取脚本资源。
     */
    private static final DefaultRedisScript<Long> SECKILL_SCRIPT;

    /**
     * 单线程消费者让HTTP请求无需等待MySQL写入。
     */
    private static final ExecutorService ORDER_EXECUTOR =
            Executors.newSingleThreadExecutor();

    static {
        SECKILL_SCRIPT = new DefaultRedisScript<>();
        SECKILL_SCRIPT.setLocation(new ClassPathResource("seckill.lua"));
        SECKILL_SCRIPT.setResultType(Long.class);
    }

    /**
     * 有界队列限制JVM内等待订单的数量，避免无限占用内存。
     */
    private final BlockingQueue<VoucherOrder> orderTasks =
            new ArrayBlockingQueue<>(ORDER_QUEUE_CAPACITY);

    @Resource
    private RedisIdWorker redisIdWorker;

    @Resource
    private ISeckillVoucherService seckillVoucherService;

    @Resource
    private RedissonClient redissonClient;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    /**
     * 后台线程必须通过Spring代理调用事务方法，不能使用this直接调用。
     */
    private IVoucherOrderService proxy;

    /**
     * Spring完成Bean创建和依赖注入后启动订单消费者。
     */
    @PostConstruct
    private void initializeOrderHandler() {
        ORDER_EXECUTOR.submit(new VoucherOrderHandler());
    }

    /**
     * 容器关闭时中断阻塞在take()上的消费者并释放线程池资源。
     */
    @PreDestroy
    private void shutdownOrderHandler() {
        ORDER_EXECUTOR.shutdownNow();
    }

    /**
     * 请求线程只负责Redis资格判断和订单排队。
     */
    @Override
    public Result seckillVoucher(Long voucherId) {
        // 1. 空ID不能用于拼接Redis key。
        if (voucherId == null) {
            return Result.fail("优惠券id不能为空");
        }

        // 2. 一人一单依赖当前登录用户。
        UserDTO currentUser = UserHolder.getUser();
        if (currentUser == null) {
            return Result.fail("请先登录");
        }

        Long userId = currentUser.getId();
        String stockKey = SECKILL_STOCK_KEY + voucherId;
        String orderKey = SECKILL_ORDER_KEY + voucherId;

        /*
         * 3. Lua在Redis中原子完成库存判断、一人一单判断、
         * 库存扣减和用户资格记录。
         */
        Long scriptResult = stringRedisTemplate.execute(
                SECKILL_SCRIPT,
                Arrays.asList(stockKey, orderKey),
                userId.toString()
        );

        Result failureResult = resolveScriptFailure(scriptResult);
        if (failureResult != null) {
            return failureResult;
        }

        // 4. 请求线程提前生成订单ID，使接口无需等待MySQL写入。
        long orderId = redisIdWorker.nextId("order");
        VoucherOrder voucherOrder = new VoucherOrder();
        voucherOrder.setId(orderId);
        voucherOrder.setUserId(userId);
        voucherOrder.setVoucherId(voucherId);

        /*
         * 5. 先保存当前AOP调用中的Spring代理，再把订单交给后台线程。
         * BlockingQueue的put/take也提供生产者与消费者之间的内存可见性。
         */
        proxy = (IVoucherOrderService) AopContext.currentProxy();

        try {
            /*
             * 当前课程阶段使用内存阻塞队列演示异步下单；
             * 后续会改为Redis Stream解决持久化、ACK和失败重试问题。
             */
            orderTasks.put(voucherOrder);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Result.fail("订单排队被中断");
        }

        return Result.ok(orderId);
    }

    /**
     * 返回null表示Lua返回0，用户取得了秒杀资格。
     */
    private Result resolveScriptFailure(Long scriptResult) {
        if (scriptResult == null) {
            return Result.fail("秒杀服务暂时不可用");
        }

        long resultCode = scriptResult.longValue();
        if (resultCode == SCRIPT_STOCK_NOT_ENOUGH) {
            return Result.fail("秒杀券库存不足");
        }
        if (resultCode == SCRIPT_DUPLICATE_ORDER) {
            return Result.fail("不能重复购买同一张优惠券");
        }
        if (resultCode == SCRIPT_STOCK_NOT_INITIALIZED) {
            return Result.fail("秒杀券库存尚未初始化");
        }
        if (resultCode != SCRIPT_SUCCESS) {
            return Result.fail("秒杀资格校验失败");
        }
        return null;
    }

    /**
     * 后台线程持续从阻塞队列中获取订单。
     */
    private class VoucherOrderHandler implements Runnable {

        @Override
        public void run() {
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    /*
                     * 队列为空时take会阻塞线程，
                     * 新订单进入队列后才继续执行，避免CPU空转。
                     */
                    VoucherOrder voucherOrder = orderTasks.take();
                    handleVoucherOrder(voucherOrder);
                } catch (InterruptedException e) {
                    // 应用关闭时恢复中断状态并正常结束消费者线程。
                    Thread.currentThread().interrupt();
                    log.info("秒杀订单消费者线程已停止");
                    return;
                } catch (RuntimeException e) {
                    /*
                     * 单条订单失败不能终止整个消费者，
                     * 否则后续订单会永久积压在队列中。
                     */
                    log.error("异步秒杀订单处理失败", e);
                }
            }
        }
    }

    /**
     * 对从队列取出的订单执行用户级并发保护。
     */
    private void handleVoucherOrder(VoucherOrder voucherOrder) {
        Long userId = voucherOrder.getUserId();
        RLock lock = redissonClient.getLock(LOCK_ORDER_KEY + userId);

        /*
         * 订单已经从队列移除，因此这里等待取得用户锁，
         * 避免一次tryLock失败就静默丢弃已经取得Redis资格的订单。
         * 未指定租约时间时，Redisson WatchDog会为仍在执行的业务自动续期。
         */
        lock.lock();

        try {
            // 通过Spring代理调用，保证createVoucherOrder上的事务生效。
            proxy.createVoucherOrder(voucherOrder);
        } finally {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    /**
     * 后台消费者调用的MySQL事务方法。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void createVoucherOrder(VoucherOrder voucherOrder) {
        /*
         * 后台线程不能读取请求线程的UserHolder，
         * 用户ID和优惠券ID必须从入队时创建的订单对象中获取。
         */
        Long userId = voucherOrder.getUserId();
        Long voucherId = voucherOrder.getVoucherId();

        // 1. 数据库再次校验一人一单，为绕过Redis的异常写入路径提供第二层保护。
        int orderCount = lambdaQuery()
                .eq(VoucherOrder::getUserId, userId)
                .eq(VoucherOrder::getVoucherId, voucherId)
                .count();

        if (orderCount > 0) {
            log.warn(
                    "数据库已存在秒杀订单，userId={}, voucherId={}",
                    userId,
                    voucherId
            );
            return;
        }

        /*
         * 2. stock > 0与库存扣减放在同一条SQL中，
         * 防止多个用户同时扣减最后一份库存造成超卖。
         */
        boolean stockUpdated = seckillVoucherService.lambdaUpdate()
                .setSql("stock = stock - 1")
                .eq(SeckillVoucher::getVoucherId, voucherId)
                .gt(SeckillVoucher::getStock, 0)
                .update();

        if (!stockUpdated) {
            throw new IllegalStateException("数据库秒杀库存不足");
        }

        /*
         * 3. 保存失败必须抛出异常，
         * Spring才能回滚前面的数据库库存扣减。
         */
        if (!save(voucherOrder)) {
            throw new IllegalStateException("秒杀订单创建失败");
        }
    }
}
