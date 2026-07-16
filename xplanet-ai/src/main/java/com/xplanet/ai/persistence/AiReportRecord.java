package com.xplanet.ai.persistence;

import com.xplanet.api.vo.AiReportVO;
import lombok.Data;

import java.time.LocalDateTime;

@Data
public class AiReportRecord {
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

    public AiReportVO toView() {
        AiReportVO view = new AiReportVO();
        view.setId(id);
        view.setTaskId(taskId);
        view.setRunId(runId);
        view.setVersion(version);
        view.setStatus(status);
        view.setTitle(title);
        view.setContent(content);
        view.setQualityScore(qualityScore);
        view.setPublishArticleId(publishArticleId);
        view.setCreateTime(createTime);
        view.setUpdateTime(updateTime);
        return view;
    }
}
