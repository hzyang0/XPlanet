package com.xplanet.ai.service;

import com.xplanet.ai.persistence.AiModelUsageRecord;
import com.xplanet.ai.persistence.AiReportRecord;
import com.xplanet.ai.persistence.AiResultMapper;
import com.xplanet.api.vo.AiReportVO;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AiReportQueryServiceTest {

    @Test
    void shouldExposeModelUsageWithTraceableReport() {
        AiResultMapper mapper = mock(AiResultMapper.class);
        AiReportQueryService service = new AiReportQueryService(mapper);
        AiReportRecord report = new AiReportRecord();
        report.setId(11L);
        report.setTaskId(7L);
        report.setRunId("run-1");
        report.setTitle("Report");
        report.setContent("Content");
        AiModelUsageRecord usage = new AiModelUsageRecord();
        usage.setNodeName("WRITER");
        usage.setProvider("offline-demo");
        usage.setModel("deterministic");
        usage.setInputTokens(120);
        usage.setOutputTokens(80);
        usage.setEstimatedCost(BigDecimal.ZERO);
        usage.setLatencyMs(25L);
        usage.setRetryCount(0);

        when(mapper.findOwnedReport(7L, 3L)).thenReturn(report);
        when(mapper.listSources(7L, "run-1")).thenReturn(List.of());
        when(mapper.listEvidence(7L, "run-1")).thenReturn(List.of());
        when(mapper.listCitations(11L)).thenReturn(List.of());
        when(mapper.listUsage("run-1")).thenReturn(List.of(usage));

        AiReportVO result = service.get(3L, 7L);

        assertThat(result.getUsage()).singleElement().satisfies(item -> {
            assertThat(item.getNodeName()).isEqualTo("WRITER");
            assertThat(item.getInputTokens()).isEqualTo(120);
            assertThat(item.getOutputTokens()).isEqualTo(80);
            assertThat(item.getLatencyMs()).isEqualTo(25L);
        });
    }
}
