package com.xplanet.api.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AiResearchResult implements Serializable {
    private static final long serialVersionUID = 1L;

    private Long taskId;
    private String runId;
    private String title;
    private String content;
    private Double qualityScore;
    private String provider;
    @Builder.Default
    private List<Source> sources = new ArrayList<>();
    @Builder.Default
    private List<Evidence> evidence = new ArrayList<>();
    @Builder.Default
    private List<Citation> citations = new ArrayList<>();
    @Builder.Default
    private List<Usage> usage = new ArrayList<>();

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Source implements Serializable {
        private String sourceRef;
        private String url;
        private String title;
        private String contentHash;
        private String retrievedAt;
        private String metadataJson;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Evidence implements Serializable {
        private String evidenceRef;
        private String sourceRef;
        private String locator;
        private String content;
        private String contentHash;
        private Double score;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Citation implements Serializable {
        private String claimId;
        private String evidenceRef;
        private Double supportScore;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Usage implements Serializable {
        private String nodeName;
        private String provider;
        private String model;
        private Integer inputTokens;
        private Integer outputTokens;
        private BigDecimal estimatedCost;
        private Long latencyMs;
        private Integer retryCount;
    }
}
