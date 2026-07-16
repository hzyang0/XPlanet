package com.xplanet.api.vo;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Data
public class AiReportVO {
    private Long id;
    private Long taskId;
    private String runId;
    private Integer version;
    private String status;
    private String title;
    private String content;
    private Double qualityScore;
    private Long publishArticleId;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
    private List<SourceVO> sources = new ArrayList<>();
    private List<EvidenceVO> evidence = new ArrayList<>();
    private List<CitationVO> citations = new ArrayList<>();

    @Data
    public static class SourceVO {
        private Long id;
        private String url;
        private String title;
        private String contentHash;
        private LocalDateTime retrievedTime;
    }

    @Data
    public static class EvidenceVO {
        private Long id;
        private Long sourceId;
        private String locator;
        private String content;
        private Double score;
    }

    @Data
    public static class CitationVO {
        private String claimId;
        private Long evidenceId;
        private Double supportScore;
    }
}
