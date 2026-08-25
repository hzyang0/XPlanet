package com.xplanet.seckill.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.xplanet.api.vo.SeckillRequestVO;
import com.xplanet.api.vo.SeckillSubmitVO;
import com.xplanet.common.auth.UserContext;
import com.xplanet.common.response.R;
import com.xplanet.seckill.domain.SeckillActivity;
import com.xplanet.seckill.mapper.ActivityMapper;
import com.xplanet.seckill.service.SeckillService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController @RequestMapping("/api/seckill") @RequiredArgsConstructor
public class SeckillController {
    private final SeckillService seckillService; private final ActivityMapper activityMapper;
    @GetMapping("/activities") public R<List<SeckillActivity>> activities() {
        return R.ok(activityMapper.selectList(new LambdaQueryWrapper<SeckillActivity>().eq(SeckillActivity::getStatus,1).orderByAsc(SeckillActivity::getId)));
    }
    @PostMapping("/activities/{activityId}/orders") public R<SeckillSubmitVO> submit(@PathVariable Long activityId) {
        return R.ok(seckillService.submit(activityId, UserContext.getUserId()));
    }
    @GetMapping("/requests/{requestId}") public R<SeckillRequestVO> request(@PathVariable Long requestId) {
        return R.ok(seckillService.query(requestId, UserContext.getUserId()));
    }
    /** Demo administration endpoint. Production must be protected by a role instead of exposing it to every user. */
    @PostMapping("/admin/activities/{activityId}/warmup") public R<Void> warmup(@PathVariable Long activityId) { seckillService.warm(activityId); return R.ok(null); }
}
