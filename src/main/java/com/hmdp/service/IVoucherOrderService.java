package com.hmdp.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.hmdp.dto.Result;
import com.hmdp.entity.VoucherOrder;

/**
 * 优惠券订单业务接口。
 */
public interface IVoucherOrderService extends IService<VoucherOrder> {

    /**
     * 在Redis中校验秒杀资格，并把订单放入异步队列。
     */
    Result seckillVoucher(Long voucherId);

    /**
     * 在事务中扣减MySQL库存并保存异步秒杀订单。
     *
     * 后台线程不能读取请求线程中的UserHolder，
     * 因此必须通过订单对象传递订单ID、用户ID和优惠券ID。
     */
    void createVoucherOrder(VoucherOrder voucherOrder);
}
