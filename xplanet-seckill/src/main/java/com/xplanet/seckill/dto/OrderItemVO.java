package com.xplanet.seckill.dto;
import lombok.Data;
import java.math.BigDecimal;
@Data public class OrderItemVO { private String productName; private BigDecimal price; private Integer quantity; }
