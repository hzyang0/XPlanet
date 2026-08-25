package com.xplanet.seckill.dto;
import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
@Data public class OrderVO { private Long id; private String orderNo; private BigDecimal totalAmount; private String status; private LocalDateTime createTime; private List<OrderItemVO> items; }
