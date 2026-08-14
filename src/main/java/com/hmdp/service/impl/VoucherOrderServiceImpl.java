package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
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
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Lazy;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.RedisSystemException;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.connection.stream.StreamReadOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.CollectionUtils;

import javax.annotation.PreDestroy;
import javax.annotation.Resource;
import java.time.Duration;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static com.hmdp.utils.RedisConstants.LOCK_ORDER_KEY;
import static com.hmdp.utils.RedisConstants.SECKILL_ORDER_KEY;
import static com.hmdp.utils.RedisConstants.SECKILL_ORDER_STREAM_CONSUMER;
import static com.hmdp.utils.RedisConstants.SECKILL_ORDER_STREAM_GROUP;
import static com.hmdp.utils.RedisConstants.SECKILL_ORDER_STREAM_KEY;
import static com.hmdp.utils.RedisConstants.SECKILL_STOCK_KEY;

/**
 * 优惠券订单业务实现类。
 */
@Slf4j
@Service
public class VoucherOrderServiceImpl
        extends ServiceImpl<VoucherOrderMapper, VoucherOrder>
        implements IVoucherOrderService, ApplicationRunner {

    private static final long SCRIPT_SUCCESS = 0L;
    private static final long SCRIPT_STOCK_NOT_ENOUGH = 1L;
    private static final long SCRIPT_DUPLICATE_ORDER = 2L;
    private static final long SCRIPT_STOCK_NOT_INITIALIZED = 3L;
    private static final Duration STREAM_BLOCK_TIMEOUT = Duration.ofSeconds(2);
    private static final long CONSUME_FAILURE_RETRY_MILLIS = 100L;

    /**
     * 脚本对象全类共享，避免每次秒杀请求重新读取脚本资源。
     */
    private static final DefaultRedisScript<Long> SECKILL_SCRIPT;

    static {
        SECKILL_SCRIPT = new DefaultRedisScript<>();
        SECKILL_SCRIPT.setLocation(new ClassPathResource("seckill.lua"));
        SECKILL_SCRIPT.setResultType(Long.class);
    }

    /**
     * Stream订单消费者使用独立单线程，避免HTTP请求等待MySQL落库。
     */
    private final ExecutorService orderExecutor =
            Executors.newSingleThreadExecutor(runnable -> {
                Thread thread = new Thread(runnable);
                thread.setName("voucher-order-stream-consumer");
                return thread;
            });

    @Resource
    private RedisIdWorker redisIdWorker;

    @Resource
    private ISeckillVoucherService seckillVoucherService;

    @Resource
    private RedissonClient redissonClient;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    /**
     * 后台消费者必须通过Spring代理调用事务方法。
     * Lazy避免当前Service创建时立即解析自身依赖而形成循环依赖。
     */
    @Lazy
    @Resource
    private IVoucherOrderService voucherOrderServiceProxy;

    /**
     * 应用启动完成后先保证消费组存在，再启动后台订单消费者。
     */
    @Override
    public void run(ApplicationArguments args) {
        createStreamConsumerGroup();
        orderExecutor.submit(new VoucherOrderHandler());
    }

    /**
     * Spring Data Redis会通过MKSTREAM在Stream不存在时创建空Stream。
     */
    private void createStreamConsumerGroup() {
        try {
            stringRedisTemplate.opsForStream().createGroup(
                    SECKILL_ORDER_STREAM_KEY,
                    ReadOffset.from("0"),
                    SECKILL_ORDER_STREAM_GROUP
            );
        } catch (RedisSystemException e) {
            Throwable rootCause = NestedExceptionUtils.getMostSpecificCause(e);
            String message = rootCause.getMessage();

            // 应用重复启动时消费组已经存在，BUSYGROUP属于正常情况。
            if (message == null || !message.contains("BUSYGROUP")) {
                throw e;
            }
        }
    }

    /**
     * 容器关闭时中断阻塞读取，并释放消费者线程池。
     */
    @PreDestroy
    private void shutdownOrderHandler() {
        orderExecutor.shutdownNow();
    }

    /**
     * TODO 学习任务1：
     * 生成订单ID，并调用Lua原子完成资格判断、库存扣减和Stream消息投递。
     */
    @Override
    public Result seckillVoucher(Long voucherId) {
        if(voucherId == null){
            return Result.fail("优惠卷id不能为空");
        }
        UserDTO currentUser = UserHolder.getUser();
        if(currentUser == null){
            return Result.fail("请先登录");
        }
        Long userId = currentUser.getId();

        Long orderId=redisIdWorker.nextId("order");

        Long scriptResult = stringRedisTemplate.execute(SECKILL_SCRIPT, Arrays.asList(
                        SECKILL_STOCK_KEY + voucherId,
                        SECKILL_ORDER_KEY + voucherId,
                        SECKILL_ORDER_STREAM_KEY
                ),
                userId.toString(),
                voucherId.toString(),
                Long.toString(orderId)
        );
        Result failureResult =
                resolveScriptFailure(scriptResult);
        if(failureResult!=null) {
            return failureResult;
        }
        return Result.ok(orderId);
    }

    /**
     * 返回null表示Lua返回0，用户取得秒杀资格且订单消息已经写入Stream。
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
     * TODO 学习任务2：
     * 使用消费者组阻塞读取“>”位置的新消息，异常时转入Pending List处理。
     */
    private class VoucherOrderHandler implements Runnable {

        @Override
        public void run() {
            /*
             * 应用可能在数据库处理完成前宕机，导致消息已经进入Pending但尚未ACK。
             * 启动时先恢复历史消息，避免它们一直留在Pending List中。
             */
            handlePendingMessages();

            while (!Thread.currentThread().isInterrupted()) {
                try {
                    // 1. 使用“>”读取当前消费组尚未分配的新订单消息
                    List<MapRecord<String, Object, Object>> records =
                            readNewOrderMessage();

                    // 阻塞读取超时后可能没有消息，此时继续等待即可
                    if (CollectionUtils.isEmpty(records)) {
                        continue;
                    }

                    // 2. 完成MySQL事务后再确认消息
                    processOrderRecord(records.get(0));
                } catch (RuntimeException e) {
                    // 容器关闭时不再进入Pending重试流程
                    if (Thread.currentThread().isInterrupted()) {
                        log.info("Redis Stream秒杀订单消费者已停止");
                        return;
                    }

                    log.error("读取或处理Redis Stream秒杀订单失败", e);

                    // 3. 消费失败的消息会进入Pending List，需要单独重新处理
                    handlePendingMessages();
                    pauseAfterFailure();
                }
            }
        }
    }

    private List<MapRecord<String, Object, Object>> readNewOrderMessage() {
        List<MapRecord<String, Object, Object>> records =
                stringRedisTemplate.opsForStream().read(
                        Consumer.from(
                                SECKILL_ORDER_STREAM_GROUP,
                                SECKILL_ORDER_STREAM_CONSUMER
                        ),
                        StreamReadOptions.empty()
                                .count(1)
                                .block(STREAM_BLOCK_TIMEOUT),
                        StreamOffset.create(
                                SECKILL_ORDER_STREAM_KEY,
                                ReadOffset.lastConsumed()
                        )
                );
        return  records==null?Collections.emptyList():records;

    }


    /**
     * TODO 学习任务3：
     * 从偏移量“0”读取当前消费者尚未ACK的Pending消息并重试。
     */
    private void handlePendingMessages() {
        while (!Thread.currentThread().isInterrupted()) {
            try {

                List<MapRecord<String, Object, Object>> records =
                        stringRedisTemplate.opsForStream().read(
                                Consumer.from(
                                        SECKILL_ORDER_STREAM_GROUP,
                                        SECKILL_ORDER_STREAM_CONSUMER
                                ),
                                StreamReadOptions.empty().count(1),
                                StreamOffset.create(
                                        SECKILL_ORDER_STREAM_KEY,
                                        ReadOffset.from("0")
                                )
                        );

                if(CollectionUtils.isEmpty(records)){
                    return;
                }
                processOrderRecord(records.get(0));
            }catch (RuntimeException e){
                if (Thread.currentThread().isInterrupted()) {
                    return;
                }
                log.error("处理Redis Stream Pending订单失败", e);
                pauseAfterFailure();
            }
        }

        // 核心消费逻辑由学习者手敲。
    }

    /**
     * TODO 学习任务4：
     * 将Stream记录转换为VoucherOrder，完成MySQL事务后再ACK。
     */
    /**
     * 将Stream消息转换成订单，数据库事务成功后再ACK。
     */
    private void processOrderRecord(
            MapRecord<String, Object, Object> record) {

        /*
         * Lua写入Stream的字段名称与VoucherOrder属性名称保持一致，
         * 因此可以将id、userId、voucherId直接转换成订单对象。
         */
        VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(
                record.getValue(),
                new VoucherOrder(),
                true
        );

        /*
         * 字段不完整的消息不能直接访问数据库。
         * 抛出异常后消息不会ACK，会留在Pending List中等待排查。
         */
        if (voucherOrder.getId() == null
                || voucherOrder.getUserId() == null
                || voucherOrder.getVoucherId() == null) {
            throw new IllegalArgumentException(
                    "Redis Stream订单消息字段不完整，recordId=" + record.getId()
            );
        }

        // 数据库事务成功返回后，才允许确认消息
        handleVoucherOrder(voucherOrder);

        Long acknowledged =
                stringRedisTemplate.opsForStream().acknowledge(
                        SECKILL_ORDER_STREAM_KEY,
                        SECKILL_ORDER_STREAM_GROUP,
                        record.getId()
                );

        /*
         * ACK失败时不能只记录日志后继续消费，
         * 抛出异常可以让外层进入Pending消息恢复流程。
         */
        if (acknowledged == null || acknowledged == 0L) {
            throw new IllegalStateException(
                    "Redis Stream订单消息ACK失败，recordId="
                            + record.getId()
                            + ", orderId="
                            + voucherOrder.getId()
            );
        }
    }

    /**
     * 失败后短暂等待，避免异常期间高速循环占满CPU和日志。
     */
    private void pauseAfterFailure() {
        try {
            Thread.sleep(CONSUME_FAILURE_RETRY_MILLIS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Redisson按用户ID保护订单业务临界区。
     */
    private void handleVoucherOrder(VoucherOrder voucherOrder) {
        Long userId = voucherOrder.getUserId();
        RLock lock = redissonClient.getLock(LOCK_ORDER_KEY + userId);

        /*
         * 不指定固定租约时间，让WatchDog在业务仍执行时自动续期，
         * 防止MySQL事务未完成时锁提前过期。
         */
        lock.lock();

        try {
            // 通过Spring代理调用，保证createVoucherOrder上的事务生效。
            voucherOrderServiceProxy.createVoucherOrder(voucherOrder);
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
        Long userId = voucherOrder.getUserId();
        Long voucherId = voucherOrder.getVoucherId();

        // 1. 数据库再次校验一人一单，为重复投递提供第二层幂等保护。
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
         * stock > 0和库存扣减位于同一条SQL中，
         * 防止并发扣减最后一份库存时发生超卖。
         */
        boolean stockUpdated = seckillVoucherService.lambdaUpdate()
                .setSql("stock = stock - 1")
                .eq(SeckillVoucher::getVoucherId, voucherId)
                .gt(SeckillVoucher::getStock, 0)
                .update();

        if (!stockUpdated) {
            throw new IllegalStateException("数据库秒杀库存不足");
        }

        // 保存失败必须抛出异常，使Spring回滚同一事务内的库存扣减。
        if (!save(voucherOrder)) {
            throw new IllegalStateException("秒杀订单创建失败");
        }
    }
}
