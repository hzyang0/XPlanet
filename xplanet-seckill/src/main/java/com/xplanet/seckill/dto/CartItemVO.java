package com.xplanet.seckill.dto;
import lombok.Data;
import java.math.BigDecimal;
@Data public class CartItemVO {
    private Long productId; private String name; private String subtitle; private String cover;
    private BigDecimal price; private Integer stock; private Integer quantity; private BigDecimal subtotal;
}
