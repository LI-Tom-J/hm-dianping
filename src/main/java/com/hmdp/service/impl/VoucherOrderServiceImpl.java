package com.hmdp.service.impl;

import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.UserHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.time.LocalDateTime;

/**
 * <p>
 *  服务实现类
 * </p>
**/
@Service
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {

    @Resource
    private RedisIdWorker redisIdWorker;
    @Resource
    private ISeckillVoucherService seckillVoucherService;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Result seckillVoucher(Long voucherId) {

        if(voucherId==null){
            return Result.fail("优惠卷id不能为空");
        }

        UserDTO currentUser= UserHolder.getUser();
        if(currentUser==null){
            return Result.fail("请先登录");
        }

        SeckillVoucher seckillVoucher=seckillVoucherService
                .getById(voucherId);
        if(seckillVoucher==null){
            return Result.fail("秒杀卷不存在");
        }

        LocalDateTime now=LocalDateTime.now();
        if(now.isBefore(seckillVoucher.getBeginTime())){
            return Result.fail("秒杀尚未开始");
        }

        if(now.isAfter(seckillVoucher.getEndTime())){
            return Result.fail("秒杀已结束");
        }

        if(seckillVoucher.getStock()==null||seckillVoucher.getStock()<=0){
            return Result.fail("优惠卷库存不足");
        }

        int orderCount=lambdaQuery()
                .eq(VoucherOrder::getUserId,currentUser.getId())
                .eq(VoucherOrder::getVoucherId,voucherId)
                .count();

        if(orderCount>0){
            return Result.fail("不能重复购买一张优惠卷");
        }

        boolean stockUpdated = seckillVoucherService.lambdaUpdate()
                .setSql("stock=stock-1")
                .eq(SeckillVoucher::getVoucherId,voucherId)
                .gt(SeckillVoucher::getStock,0)
                .update();
        if(!stockUpdated){
            return Result.fail("优惠卷库存不足");
        }

        long orderId=redisIdWorker.nextId("order");

        VoucherOrder voucherOrder=new VoucherOrder();
        voucherOrder.setId(orderId);
        voucherOrder.setUserId(currentUser.getId());
        voucherOrder.setVoucherId(voucherId);

        boolean orderSaved = save(voucherOrder);
        if(!orderSaved){
            throw new IllegalStateException("秒杀订单创建失败");
        }

        return Result.ok(orderId);
    }
}
