package com.xplanet.seckill.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.xplanet.api.dto.SeckillOrderMessage;
import com.xplanet.api.vo.SeckillRequestVO;
import com.xplanet.api.vo.SeckillSubmitVO;
import com.xplanet.common.exception.BizException;
import com.xplanet.common.response.ErrorCode;
import com.xplanet.common.util.JsonUtil;
import com.xplanet.seckill.domain.*;
import com.xplanet.seckill.mapper.*;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.LocalDateTime;
import java.util.UUID;

@Service @RequiredArgsConstructor
public class SeckillService {
    private final ActivityMapper activityMapper;
    private final RequestMapper requestMapper;
    private final RedisReservationService reservationService;
    private final SeckillSubmissionTxService submissionTxService;

    /** Fast path: Lua reserves capacity; the same request transaction durably records an outbox command. */
    public SeckillSubmitVO submit(Long activityId, Long userId) {
        SeckillActivity activity = activityMapper.selectById(activityId);
        if (activity == null || activity.getStatus() != 1 || LocalDateTime.now().isBefore(activity.getStartTime()) || LocalDateTime.now().isAfter(activity.getEndTime())) {
            throw new BizException(ErrorCode.NOT_FOUND);
        }
        long admitted = reservationService.reserve(activityId, userId);
        if (admitted == 1) return new SeckillSubmitVO(null, "REJECTED", "商品已售罄");
        if (admitted == 2) return existing(activityId, userId);
        if (admitted != 0) return new SeckillSubmitVO(null, "REJECTED", "活动尚未预热，请稍后重试");
        try {
            SeckillSubmitVO result=submissionTxService.save(activity, userId);
            if ("DUPLICATE".equals(result.getStatus())) {
                // Redis may have been rebuilt while MySQL still has the user's historical request.
                reservationService.release(activityId, userId);
                return existing(activityId, userId);
            }
            return result;
        } catch (RuntimeException ex) {
            reservationService.release(activityId, userId);
            throw ex;
        }
    }

    public SeckillRequestVO query(Long requestId, Long userId) {
        SeckillRequest r = requestMapper.selectById(requestId);
        if (r == null || !r.getUserId().equals(userId)) throw new BizException(ErrorCode.NOT_FOUND);
        SeckillRequestVO vo = new SeckillRequestVO();
        vo.setRequestId(r.getId()); vo.setActivityId(r.getActivityId()); vo.setSkuId(r.getSkuId()); vo.setStatus(r.getStatus());
        vo.setOrderId(r.getOrderId()); vo.setFailReason(r.getFailReason()); vo.setCreateTime(r.getCreateTime()); return vo;
    }

    public void warm(Long activityId) {
        SeckillActivity a = activityMapper.selectById(activityId);
        if (a == null) throw new BizException(ErrorCode.NOT_FOUND);
        reservationService.warm(activityId, a.getAvailableStock());
    }

    private SeckillSubmitVO existing(Long activityId, Long userId) {
        SeckillRequest r = requestMapper.selectOne(new LambdaQueryWrapper<SeckillRequest>()
                .eq(SeckillRequest::getActivityId, activityId).eq(SeckillRequest::getUserId, userId));
        return r == null ? new SeckillSubmitVO(null, "REJECTED", "请勿重复下单") : new SeckillSubmitVO(r.getId(), r.getStatus(), "请使用原请求查询结果");
    }
}
