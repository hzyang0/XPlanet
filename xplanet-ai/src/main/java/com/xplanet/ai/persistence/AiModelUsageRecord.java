package com.xplanet.ai.persistence;

import com.xplanet.api.vo.AiReportVO;
import lombok.Data;

import java.math.BigDecimal;

@Data
public class AiModelUsageRecord {
    private String nodeName;
    private String provider;
    private String model;
    private Integer inputTokens;
    private Integer outputTokens;
    private BigDecimal estimatedCost;
    private Long latencyMs;
    private Integer retryCount;

    public AiReportVO.UsageVO toView() {
        AiReportVO.UsageVO view = new AiReportVO.UsageVO();
        view.setNodeName(nodeName);
        view.setProvider(provider);
        view.setModel(model);
        view.setInputTokens(inputTokens);
        view.setOutputTokens(outputTokens);
        view.setEstimatedCost(estimatedCost);
        view.setLatencyMs(latencyMs);
        view.setRetryCount(retryCount);
        return view;
    }
}
