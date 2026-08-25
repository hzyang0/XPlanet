package com.xplanet.seckill.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.xplanet.api.dto.SeckillOrderMessage;
import com.xplanet.common.util.JsonUtil;
import com.xplanet.seckill.domain.OrderOutbox;
import com.xplanet.seckill.mapper.OutboxMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import java.time.LocalDateTime;
import java.util.List;

@Slf4j @Component @RequiredArgsConstructor
public class OutboxRelay {
    public static final String TOPIC = "xp_seckill_order_topic";
    private final OutboxMapper outboxMapper; private final RocketMQTemplate rocketMQTemplate;
    @Value("${seckill.outbox.batch-size:50}") private int batchSize;
    @Scheduled(fixedDelay = 1000)
    public void relay() {
        List<OrderOutbox> events=outboxMapper.selectList(new LambdaQueryWrapper<OrderOutbox>()
                .in(OrderOutbox::getStatus, "PENDING", "RETRY").le(OrderOutbox::getNextRetryTime, LocalDateTime.now())
                .orderByAsc(OrderOutbox::getId).last("LIMIT " + batchSize));
        for (OrderOutbox event:events) publish(event);
    }
    private void publish(OrderOutbox event) {
        try {
            SeckillOrderMessage cmd=JsonUtil.fromJson(event.getPayload(), SeckillOrderMessage.class);
            rocketMQTemplate.syncSend(TOPIC, MessageBuilder.withPayload(cmd).build(), 3000);
            event.setStatus("SENT"); event.setSentTime(LocalDateTime.now()); event.setLastError(null); outboxMapper.updateById(event);
        } catch (Exception e) {
            event.setStatus("RETRY"); event.setRetryCount(event.getRetryCount()+1); event.setLastError(trim(e.getMessage()));
            event.setNextRetryTime(LocalDateTime.now().plusSeconds(Math.min(60, 1L << Math.min(6,event.getRetryCount())))); outboxMapper.updateById(event);
            log.warn("seckill outbox publish retry, eventId={}", event.getEventId(), e);
        }
    }
    private String trim(String value) { return value == null ? "unknown" : value.substring(0, Math.min(450,value.length())); }
}
