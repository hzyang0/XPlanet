package com.xplanet.seckill.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.xplanet.api.dto.SeckillOrderMessage;
import com.xplanet.seckill.domain.SeckillOrder;
import com.xplanet.seckill.domain.SeckillRequest;
import com.xplanet.seckill.mapper.ActivityMapper;
import com.xplanet.seckill.mapper.OrderMapper;
import com.xplanet.seckill.mapper.RequestMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.LocalDateTime;

/** At-least-once delivery is expected. event_id and (activity_id,user_id) make it harmless. */
@Slf4j @Service @RequiredArgsConstructor
@RocketMQMessageListener(topic = OutboxRelay.TOPIC, consumerGroup = "xp-seckill-order-consumer")
public class OrderConsumer implements RocketMQListener<SeckillOrderMessage> {
    private final OrderMapper orderMapper; private final RequestMapper requestMapper;
    private final ActivityMapper activityMapper; private final RedisReservationService reservationService;
    @Override @Transactional public void onMessage(SeckillOrderMessage m) {
        if (orderMapper.selectOne(new LambdaQueryWrapper<SeckillOrder>().eq(SeckillOrder::getEventId, m.getEventId())) != null) return;
        SeckillRequest request=requestMapper.selectById(m.getRequestId());
        if (request == null || !"QUEUED".equals(request.getStatus())) return;
        if (activityMapper.deductOne(m.getActivityId()) != 1) {
            fail(request, "数据库库存不足"); reservationService.release(m.getActivityId(),m.getUserId()); return;
        }
        SeckillOrder order=new SeckillOrder(); order.setEventId(m.getEventId()); order.setActivityId(m.getActivityId());
        order.setSkuId(m.getSkuId()); order.setUserId(m.getUserId()); order.setStatus("CREATED"); order.setCreateTime(LocalDateTime.now());
        try { orderMapper.insert(order); }
        catch (DuplicateKeyException duplicate) { // a retried/different command cannot create a second order
            // We already executed the guarded DB decrement above. A duplicate must return that unit.
            activityMapper.restoreOne(m.getActivityId());
            SeckillOrder existing=orderMapper.selectOne(new LambdaQueryWrapper<SeckillOrder>().eq(SeckillOrder::getActivityId,m.getActivityId()).eq(SeckillOrder::getUserId,m.getUserId()));
            request.setStatus("SUCCEEDED"); request.setOrderId(existing.getId()); requestMapper.updateById(request); return;
        }
        request.setStatus("SUCCEEDED"); request.setOrderId(order.getId()); request.setUpdateTime(LocalDateTime.now()); requestMapper.updateById(request);
    }
    private void fail(SeckillRequest request,String reason) {
        request.setStatus("FAILED"); request.setFailReason(reason); request.setUpdateTime(LocalDateTime.now()); requestMapper.updateById(request);
    }
}
