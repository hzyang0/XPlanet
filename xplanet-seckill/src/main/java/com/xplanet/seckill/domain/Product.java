package com.xplanet.seckill.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import java.math.BigDecimal;

@Data @TableName("product")
public class Product {
    @TableId private Long id;
    private String name;
    private String subtitle;
    private String category;
    private BigDecimal price;
    private Integer stock;
    private Integer sales;
    private String cover;
    private Integer status;
}
