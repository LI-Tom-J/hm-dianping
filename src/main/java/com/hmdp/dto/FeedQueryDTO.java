package com.hmdp.dto;

import lombok.Data;

/**
 * Feed滚动分页请求参数。
 */
@Data
public class FeedQueryDTO {

    /**
     * 上一次返回的最小score，第一次请求由前端传入当前时间戳。
     */
    private Long lastId;

    /**
     * 跳过与lastId相同且已经读取过的数据，避免滚动分页出现重复。
     */
    private Integer offset = 0;
}
