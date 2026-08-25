package com.xplanet.seckill.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

@Data @TableName("cart_item")
public class CartItem {
    @TableId private Long id;
    private Long userId;
    private Long productId;
    private Integer quantity;
}
