package com.xplanet.ai.service;

import com.xplanet.ai.persistence.AiReportRecord;
import com.xplanet.ai.persistence.AiResultMapper;
import com.xplanet.api.vo.AiReportVO;
import com.xplanet.common.exception.BizException;
import com.xplanet.common.response.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class AiReportQueryService {

    private final AiResultMapper resultMapper;

    @Transactional(readOnly = true)
    public AiReportVO get(Long userId, Long taskId) {
        AiReportRecord report = resultMapper.findOwnedReport(taskId, userId);
        if (report == null) {
            throw new BizException(ErrorCode.AI_REPORT_NOT_READY);
        }
        AiReportVO view = report.toView();
        view.setSources(resultMapper.listSources(taskId, report.getRunId()).stream()
                .map(source -> source.toView()).collect(Collectors.toList()));
        view.setEvidence(resultMapper.listEvidence(taskId, report.getRunId()).stream()
                .map(evidence -> evidence.toView()).collect(Collectors.toList()));
        view.setCitations(resultMapper.listCitations(report.getId()).stream()
                .map(citation -> citation.toView()).collect(Collectors.toList()));
        view.setUsage(resultMapper.listUsage(report.getRunId()).stream()
                .map(usage -> usage.toView()).collect(Collectors.toList()));
        return view;
    }
}
