package com.xplanet.ai.persistence;

import com.xplanet.api.vo.AiReportVO;
import lombok.Data;

@Data
public class AiEvidenceRecord {
    private Long id;
    private Long taskId;
    private String runId;
    private Long sourceId;
    private String locator;
    private String content;
    private Double score;

    public AiReportVO.EvidenceVO toView() {
        AiReportVO.EvidenceVO view = new AiReportVO.EvidenceVO();
        view.setId(id);
        view.setSourceId(sourceId);
        view.setLocator(locator);
        view.setContent(content);
        view.setScore(score);
        return view;
    }
}
