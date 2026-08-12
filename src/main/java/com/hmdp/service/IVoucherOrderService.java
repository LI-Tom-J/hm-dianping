package com.hmdp.service;

import com.hmdp.dto.Result;
import com.hmdp.entity.VoucherOrder;
import com.baomidou.mybatisplus.extension.service.IService;

/**
 * <p>
 *  服务类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
public interface IVoucherOrderService extends IService<VoucherOrder> {

    /**
     * 校验秒杀资格，并控制同一用户的并发请求。
     */
    Result seckillVoucher(Long voucherId);

    /**
     * 在事务中检查重复订单、扣减库存并创建订单。
     */
    Result createVoucherOrder(Long voucherId);

}
