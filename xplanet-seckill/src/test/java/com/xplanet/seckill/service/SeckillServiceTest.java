package com.xplanet.seckill.service;

import com.xplanet.api.vo.SeckillSubmitVO;
import com.xplanet.seckill.domain.SeckillActivity;
import com.xplanet.seckill.mapper.ActivityMapper;
import com.xplanet.seckill.mapper.RequestMapper;
import org.junit.jupiter.api.Test;
import java.time.LocalDateTime;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;

class SeckillServiceTest {
    private final ActivityMapper activities=mock(ActivityMapper.class);
    private final RequestMapper requests=mock(RequestMapper.class);
    private final RedisReservationService redis=mock(RedisReservationService.class);
    private final SeckillSubmissionTxService tx=mock(SeckillSubmissionTxService.class);
    private final SeckillService service=new SeckillService(activities,requests,redis,tx);

    @Test void soldOutIsRejectedBeforeDatabaseWrite() {
        when(activities.selectById(1L)).thenReturn(active()); when(redis.reserve(1L,2L)).thenReturn(1L);
        SeckillSubmitVO result=service.submit(1L,2L);
        assertEquals("REJECTED",result.getStatus()); verifyNoInteractions(tx);
    }
    @Test void acceptedRequestIsQueuedThroughTransactionalOutboxWrite() {
        when(activities.selectById(1L)).thenReturn(active()); when(redis.reserve(1L,2L)).thenReturn(0L);
        when(tx.save(any(),eq(2L))).thenReturn(new SeckillSubmitVO(9L,"QUEUED","ok"));
        assertEquals("QUEUED",service.submit(1L,2L).getStatus()); verify(tx).save(any(),eq(2L)); verify(redis,never()).release(anyLong(),anyLong());
    }
    private SeckillActivity active() {
        SeckillActivity a=new SeckillActivity(); a.setId(1L);a.setSkuId(10L);a.setStatus(1);a.setStartTime(LocalDateTime.now().minusMinutes(1));a.setEndTime(LocalDateTime.now().plusMinutes(1));return a;
    }
}
