package com.xplanet.ai.controller;

import com.xplanet.ai.service.AiProviderService;
import com.xplanet.common.response.R;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/ai/providers")
@RequiredArgsConstructor
public class AiProviderController {

    private final AiProviderService providerService;

    @GetMapping
    public R<Map<String, Object>> capabilities() {
        return R.ok(providerService.capabilities());
    }
}
