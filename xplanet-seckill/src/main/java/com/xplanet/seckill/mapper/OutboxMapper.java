package com.xplanet.seckill.mapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.xplanet.seckill.domain.OrderOutbox;
import org.apache.ibatis.annotations.Mapper;
@Mapper public interface OutboxMapper extends BaseMapper<OrderOutbox> {}
