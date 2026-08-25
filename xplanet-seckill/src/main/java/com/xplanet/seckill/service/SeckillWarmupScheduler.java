package com.xplanet.seckill.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.xplanet.seckill.domain.SeckillActivity;
import com.xplanet.seckill.mapper.ActivityMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import java.time.LocalDateTime;

/** Pre-warms only absent Redis keys; it is safe to run repeatedly without resetting a sale. */
@Component @RequiredArgsConstructor
public class SeckillWarmupScheduler {
    private final ActivityMapper activityMapper;
    private final RedisReservationService reservationService;
    @Scheduled(initialDelay = 2000, fixedDelay = 30000)
    public void warmActiveActivities() {
        LocalDateTime now = LocalDateTime.now();
        activityMapper.selectList(new LambdaQueryWrapper<SeckillActivity>().eq(SeckillActivity::getStatus, 1)
                .le(SeckillActivity::getStartTime, now).ge(SeckillActivity::getEndTime, now))
                .forEach(activity -> reservationService.warm(activity.getId(), activity.getAvailableStock()));
    }
}
