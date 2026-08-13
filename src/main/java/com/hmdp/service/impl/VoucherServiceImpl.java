package com.hmdp.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.entity.Voucher;
import com.hmdp.mapper.VoucherMapper;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import javax.annotation.Resource;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

import static com.hmdp.utils.RedisConstants.SECKILL_STOCK_KEY;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Slf4j
@Service
public class VoucherServiceImpl extends ServiceImpl<VoucherMapper, Voucher> implements IVoucherService {

    @Resource
    private ISeckillVoucherService seckillVoucherService;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public Result queryVoucherOfShop(Long shopId) {
        // 查询优惠券信息
        List<Voucher> vouchers = getBaseMapper().queryVoucherOfShop(shopId);
        // 返回结果
        return Result.ok(vouchers);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void addSeckillVoucher(Voucher voucher) {
        validateSeckillVoucher(voucher);

        // 两张业务表必须在同一事务内成功，避免出现只有优惠券主表、没有秒杀信息的半条数据。
        if (!save(voucher)) {
            throw new IllegalStateException("优惠券保存失败");
        }

        SeckillVoucher seckillVoucher = new SeckillVoucher();
        seckillVoucher.setVoucherId(voucher.getId());
        seckillVoucher.setStock(voucher.getStock());
        seckillVoucher.setBeginTime(voucher.getBeginTime());
        seckillVoucher.setEndTime(voucher.getEndTime());

        if (!seckillVoucherService.save(seckillVoucher)) {
            throw new IllegalStateException("秒杀券保存失败");
        }

        initializeRedisStockAfterCommit(voucher);
    }

    private void validateSeckillVoucher(Voucher voucher) {
        if (voucher == null) {
            throw new IllegalArgumentException("秒杀券信息不能为空");
        }
        if (voucher.getStock() == null || voucher.getStock() <= 0) {
            throw new IllegalArgumentException("秒杀券库存必须大于0");
        }
        if (voucher.getBeginTime() == null || voucher.getEndTime() == null) {
            throw new IllegalArgumentException("秒杀起止时间不能为空");
        }
        if (!voucher.getBeginTime().isBefore(voucher.getEndTime())) {
            throw new IllegalArgumentException("秒杀开始时间必须早于结束时间");
        }
        if (!voucher.getEndTime().isAfter(LocalDateTime.now())) {
            throw new IllegalArgumentException("秒杀结束时间必须晚于当前时间");
        }
    }

    private void initializeRedisStockAfterCommit(Voucher voucher) {
        Long voucherId = voucher.getId();
        Integer stock = voucher.getStock();
        LocalDateTime endTime = voucher.getEndTime();

        /*
         * Redis不能参加当前MySQL本地事务，因此必须等数据库提交成功后再初始化库存；
         * 否则数据库回滚时Redis仍有库存，会产生一张并不存在的可抢优惠券。
         */
        TransactionSynchronizationManager.registerSynchronization(
                new TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        try {
                            Duration ttl = Duration.between(
                                    LocalDateTime.now(),
                                    endTime
                            );

                            stringRedisTemplate.opsForValue().set(
                                    SECKILL_STOCK_KEY + voucherId,
                                    stock.toString(),
                                    ttl
                            );
                        } catch (RuntimeException e) {
                            /*
                             * 此时MySQL已经提交，不能再假装回滚成功；记录券ID便于补偿初始化。
                             * Lua遇到缺失库存会返回3，从而阻止错误放行秒杀请求。
                             */
                            log.error("秒杀券已写入MySQL，但Redis库存初始化失败，voucherId={}", voucherId, e);
                        }
                    }
                }
        );
    }
}
