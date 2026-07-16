package com.xplanet.ai.controller;

import com.xplanet.ai.service.AiProgressService;
import com.xplanet.ai.service.InternalTokenVerifier;
import com.xplanet.api.dto.AiProgressEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.validation.Valid;
import java.util.Map;

@RestController
@RequestMapping("/internal/ai/tasks")
@RequiredArgsConstructor
public class AgentInternalController {

    private final InternalTokenVerifier tokenVerifier;
    private final AiProgressService progressService;

    @PostMapping("/{taskId}/progress")
    public Map<String, Boolean> progress(@RequestHeader("X-Agent-Token") String token,
                                         @PathVariable Long taskId,
                                         @Valid @RequestBody AiProgressEvent event) {
        tokenVerifier.require(token);
        progressService.append(taskId, event);
        return Map.of("accepted", true);
    }

    @GetMapping("/{taskId}/cancelled")
    public Map<String, Boolean> cancelled(@RequestHeader("X-Agent-Token") String token,
                                          @PathVariable Long taskId) {
        tokenVerifier.require(token);
        return Map.of("cancelled", progressService.isCancelled(taskId));
    }
}
