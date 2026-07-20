package com.xplanet.ai.service;

import com.xplanet.ai.persistence.AiEvidenceRecord;
import com.xplanet.ai.persistence.AiReportRecord;
import com.xplanet.ai.persistence.AiResultMapper;
import com.xplanet.ai.persistence.AiSourceRecord;
import com.xplanet.ai.persistence.AiTaskMapper;
import com.xplanet.ai.persistence.AiTaskRecord;
import com.xplanet.api.dto.AiResearchResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AiResultPersistenceServiceTest {

    private AiTaskMapper taskMapper;
    private AiResultMapper resultMapper;
    private AiResultPersistenceService service;

    @BeforeEach
    void setUp() {
        taskMapper = mock(AiTaskMapper.class);
        resultMapper = mock(AiResultMapper.class);
        service = new AiResultPersistenceService(taskMapper, resultMapper);
    }

    @Test
    void shouldPersistBoundSourcesEvidenceCitationsAndTransitionToReview() {
        when(resultMapper.insertInbox(AiResultPersistenceService.CONSUMER, "event-1")).thenReturn(1);
        when(taskMapper.findInternalForUpdate(1L)).thenReturn(task("RUNNING"));
        doAnswer(invocation -> {
            AiSourceRecord record = invocation.getArgument(0);
            record.setId(10L);
            return 1;
        }).when(resultMapper).insertSource(any(AiSourceRecord.class));
        doAnswer(invocation -> {
            AiEvidenceRecord record = invocation.getArgument(0);
            record.setId(20L);
            return 1;
        }).when(resultMapper).insertEvidence(any(AiEvidenceRecord.class));
        doAnswer(invocation -> {
            AiReportRecord record = invocation.getArgument(0);
            record.setId(30L);
            return 1;
        }).when(resultMapper).insertReport(any(AiReportRecord.class));
        when(resultMapper.insertCitation(30L, "claim-1", 20L, 0.9)).thenReturn(1);
        when(resultMapper.insertUsage(eq("run-1"), any(AiResearchResult.Usage.class))).thenReturn(1);
        when(resultMapper.markTaskWaitingReview(1L, "run-1")).thenReturn(1);
        when(resultMapper.markRunWaitingReview(1L, "run-1")).thenReturn(1);

        service.complete("event-1", result());

        verify(resultMapper).insertCitation(30L, "claim-1", 20L, 0.9);
        verify(resultMapper).insertUsage(eq("run-1"), any(AiResearchResult.Usage.class));
        verify(resultMapper).markTaskWaitingReview(1L, "run-1");
        verify(resultMapper).markRunWaitingReview(1L, "run-1");
    }

    @Test
    void shouldIgnoreDuplicateConsumerEventBeforeAnySideEffect() {
        when(resultMapper.insertInbox(AiResultPersistenceService.CONSUMER, "event-1")).thenReturn(0);

        service.complete("event-1", result());

        verify(taskMapper, never()).findInternalForUpdate(anyLong());
        verify(resultMapper, never()).insertSource(any());
    }

    @Test
    void shouldAcknowledgeResultWithoutReportWhenTaskWasCancelled() {
        when(resultMapper.insertInbox(AiResultPersistenceService.CONSUMER, "event-1")).thenReturn(1);
        when(taskMapper.findInternalForUpdate(1L)).thenReturn(task("CANCELLED"));

        service.complete("event-1", result());

        verify(resultMapper).markRunCancelled(1L, "run-1");
        verify(resultMapper, never()).insertReport(any());
    }

    @Test
    void shouldRejectCitationThatDoesNotBindToKnownEvidence() {
        AiResearchResult invalid = result();
        invalid.getCitations().get(0).setEvidenceRef("invented-evidence");
        when(resultMapper.insertInbox(AiResultPersistenceService.CONSUMER, "event-1")).thenReturn(1);
        when(taskMapper.findInternalForUpdate(1L)).thenReturn(task("RUNNING"));

        assertThatThrownBy(() -> service.complete("event-1", invalid))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("invalid citation binding");

        verify(resultMapper, never()).insertSource(any());
    }

    @Test
    void shouldRejectModelUsageBeyondTaskOutputTokenBudget() {
        AiResearchResult invalid = result();
        invalid.getUsage().get(0).setOutputTokens(8_001);
        when(resultMapper.insertInbox(AiResultPersistenceService.CONSUMER, "event-1")).thenReturn(1);
        when(taskMapper.findInternalForUpdate(1L)).thenReturn(task("RUNNING"));

        assertThatThrownBy(() -> service.complete("event-1", invalid))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("model usage exceeds output token budget");

        verify(resultMapper, never()).insertSource(any());
    }

    @Test
    void shouldRejectModelCallRecordsBeyondDynamicAgentBound() {
        AiResearchResult invalid = result();
        invalid.setUsage(new ArrayList<>(Collections.nCopies(7, invalid.getUsage().get(0))));
        AiTaskRecord constrained = task("RUNNING");
        constrained.setMaxToolCalls(1);
        when(resultMapper.insertInbox(AiResultPersistenceService.CONSUMER, "event-1")).thenReturn(1);
        when(taskMapper.findInternalForUpdate(1L)).thenReturn(constrained);

        assertThatThrownBy(() -> service.complete("event-1", invalid))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("AI result exceeds task bounds");

        verify(resultMapper, never()).insertSource(any());
    }

    private AiTaskRecord task(String status) {
        AiTaskRecord task = new AiTaskRecord();
        task.setId(1L);
        task.setCurrentRunId("run-1");
        task.setStatus(status);
        task.setMaxSources(5);
        task.setMaxToolCalls(10);
        task.setMaxTokens(8_000);
        return task;
    }

    private AiResearchResult result() {
        AiResearchResult.Source source = new AiResearchResult.Source(
                "src-1", "https://example.com/source", "Source",
                "a".repeat(64), OffsetDateTime.now().toString(), "{}");
        AiResearchResult.Evidence evidence = new AiResearchResult.Evidence(
                "ev-1", "src-1", "section 1", "supporting evidence", 0.9);
        AiResearchResult.Citation citation = new AiResearchResult.Citation("claim-1", "ev-1", 0.9);
        AiResearchResult.Usage usage = new AiResearchResult.Usage(
                "EXECUTE_TOOL", "openai", "test-model", 120, 80,
                BigDecimal.ZERO, 25L, 0);
        return AiResearchResult.builder()
                .taskId(1L).runId("run-1").title("Report").content("Content")
                .qualityScore(0.9).provider("offline-demo")
                .sources(List.of(source)).evidence(List.of(evidence)).citations(List.of(citation))
                .usage(List.of(usage))
                .build();
    }
}
