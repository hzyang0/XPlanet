package com.xplanet.seckill.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.xplanet.seckill.domain.Product;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface ProductMapper extends BaseMapper<Product> {
    @Update("UPDATE product SET stock=stock-#{quantity}, sales=sales+#{quantity} WHERE id=#{productId} AND status=1 AND stock>=#{quantity}")
    int deductStock(@Param("productId") Long productId, @Param("quantity") Integer quantity);
}
