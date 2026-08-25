package com.xplanet.seckill.mapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.xplanet.seckill.domain.SeckillActivity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Update;
@Mapper public interface ActivityMapper extends BaseMapper<SeckillActivity> {
    @Update("UPDATE seckill_activity SET available_stock=available_stock-1 WHERE id=#{activityId} AND available_stock>0 AND status=1")
    int deductOne(Long activityId);
    @Update("UPDATE seckill_activity SET available_stock=available_stock+1 WHERE id=#{activityId}")
    int restoreOne(Long activityId);
}
