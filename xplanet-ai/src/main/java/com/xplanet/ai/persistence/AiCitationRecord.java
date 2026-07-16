package com.xplanet.ai.persistence;

import com.xplanet.api.vo.AiReportVO;
import lombok.Data;

@Data
public class AiCitationRecord {
    private String claimId;
    private Long evidenceId;
    private Double supportScore;

    public AiReportVO.CitationVO toView() {
        AiReportVO.CitationVO view = new AiReportVO.CitationVO();
        view.setClaimId(claimId);
        view.setEvidenceId(evidenceId);
        view.setSupportScore(supportScore);
        return view;
    }
}
