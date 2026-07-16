package com.xplanet.ai.persistence;

import com.xplanet.api.vo.AiReportVO;
import lombok.Data;

import java.time.LocalDateTime;

@Data
public class AiSourceRecord {
    private Long id;
    private Long taskId;
    private String runId;
    private String url;
    private String title;
    private String contentHash;
    private LocalDateTime retrievedTime;
    private String metadataJson;

    public AiReportVO.SourceVO toView() {
        AiReportVO.SourceVO view = new AiReportVO.SourceVO();
        view.setId(id);
        view.setUrl(url);
        view.setTitle(title);
        view.setContentHash(contentHash);
        view.setRetrievedTime(retrievedTime);
        return view;
    }
}
