package com.xplanet.seckill.service;

import com.xplanet.api.dto.SeckillOrderMessage;
import com.xplanet.api.vo.SeckillSubmitVO;
import com.xplanet.common.util.JsonUtil;
import com.xplanet.seckill.domain.OrderOutbox;
import com.xplanet.seckill.domain.SeckillActivity;
import com.xplanet.seckill.domain.SeckillRequest;
import com.xplanet.seckill.mapper.OutboxMapper;
import com.xplanet.seckill.mapper.RequestMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.LocalDateTime;
import java.util.UUID;

/** Separate Spring bean ensures @Transactional is applied to the request+outbox write. */
@Service @RequiredArgsConstructor
public class SeckillSubmissionTxService {
    private final RequestMapper requestMapper; private final OutboxMapper outboxMapper;
    @Transactional public SeckillSubmitVO save(SeckillActivity activity, Long userId) {
        String eventId=UUID.randomUUID().toString();
        SeckillRequest request=new SeckillRequest(); request.setRequestNo(UUID.randomUUID().toString());
        request.setActivityId(activity.getId()); request.setSkuId(activity.getSkuId()); request.setUserId(userId); request.setStatus("QUEUED");
        try { requestMapper.insert(request); }
        catch (DuplicateKeyException ignored) { return new SeckillSubmitVO(null,"DUPLICATE","请勿重复下单"); }
        SeckillOrderMessage command=new SeckillOrderMessage(eventId,request.getId(),activity.getId(),activity.getSkuId(),userId);
        OrderOutbox outbox=new OrderOutbox(); outbox.setEventId(eventId); outbox.setRequestId(request.getId()); outbox.setPayload(JsonUtil.toJson(command));
        outbox.setStatus("PENDING"); outbox.setRetryCount(0); outbox.setNextRetryTime(LocalDateTime.now()); outboxMapper.insert(outbox);
        return new SeckillSubmitVO(request.getId(),"QUEUED","已进入排队，稍后查询订单状态");
    }
}
