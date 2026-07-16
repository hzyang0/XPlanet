package com.xplanet.ai.controller;

import com.xplanet.ai.service.AiTaskService;
import com.xplanet.api.request.CreateResearchTaskRequest;
import com.xplanet.api.vo.AiTaskVO;
import com.xplanet.common.auth.UserContext;
import com.xplanet.common.response.R;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import javax.validation.Valid;
import java.util.List;
import com.xplanet.ai.service.AiProgressService;

@RestController
@RequestMapping("/api/ai/tasks")
@RequiredArgsConstructor
public class AiTaskController {

    private final AiTaskService taskService;
    private final AiProgressService progressService;

    @PostMapping
    public R<AiTaskVO> create(@RequestHeader("Idempotency-Key") String idempotencyKey,
                              @Valid @RequestBody CreateResearchTaskRequest request) {
        return R.ok(taskService.create(UserContext.getUserId(), idempotencyKey, request));
    }

    @GetMapping("/{taskId}")
    public R<AiTaskVO> get(@PathVariable Long taskId) {
        return R.ok(taskService.get(UserContext.getUserId(), taskId));
    }

    @GetMapping
    public R<List<AiTaskVO>> list(@RequestParam(defaultValue = "20") int limit) {
        return R.ok(taskService.list(UserContext.getUserId(), limit));
    }

    @DeleteMapping("/{taskId}")
    public R<AiTaskVO> cancel(@PathVariable Long taskId) {
        return R.ok(taskService.cancel(UserContext.getUserId(), taskId));
    }

    @GetMapping(value = "/{taskId}/events", produces = "text/event-stream")
    public SseEmitter events(@PathVariable Long taskId,
                             @RequestHeader(value = "Last-Event-ID", required = false)
                             String lastEventId) {
        return progressService.subscribe(UserContext.getUserId(), taskId, lastEventId);
    }
}
