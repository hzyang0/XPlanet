package com.xplanet.ai.service;

import com.xplanet.ai.domain.AiTaskStatus;
import com.xplanet.ai.persistence.AiEvidenceRecord;
import com.xplanet.ai.persistence.AiReportRecord;
import com.xplanet.ai.persistence.AiResultMapper;
import com.xplanet.ai.persistence.AiSourceRecord;
import com.xplanet.ai.persistence.AiTaskMapper;
import com.xplanet.ai.persistence.AiTaskRecord;
import com.xplanet.api.dto.AiResearchResult;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.net.URI;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class AiResultPersistenceService {

    static final String CONSUMER = "xplanet-ai-agent-result";

    private final AiTaskMapper taskMapper;
    private final AiResultMapper resultMapper;

    @Transactional
    public void complete(String eventId, AiResearchResult result) {
        validateEnvelope(eventId, result);
        if (resultMapper.insertInbox(CONSUMER, eventId) == 0) {
            return;
        }
        AiTaskRecord task = taskMapper.findInternalForUpdate(result.getTaskId());
        if (task == null || !result.getRunId().equals(task.getCurrentRunId())) {
            throw new IllegalStateException("AI result does not match an active task run");
        }
        if (AiTaskStatus.CANCELLED.name().equals(task.getStatus())) {
            resultMapper.markRunCancelled(task.getId(), result.getRunId());
            return;
        }
        if (resultMapper.findByRun(task.getId(), result.getRunId()) != null) {
            return;
        }
        if (!AiTaskStatus.RUNNING.name().equals(task.getStatus())) {
            throw new IllegalStateException("AI result received while task is " + task.getStatus());
        }
        validatePayload(task, result);

        Map<String, Long> sourceIds = persistSources(task, result.getSources());
        Map<String, Long> evidenceIds = persistEvidence(task, result.getEvidence(), sourceIds);

        AiReportRecord report = new AiReportRecord();
        report.setTaskId(task.getId());
        report.setRunId(result.getRunId());
        report.setTitle(result.getTitle().trim());
        report.setContent(result.getContent());
        report.setQualityScore(result.getQualityScore());
        if (resultMapper.insertReport(report) != 1 || report.getId() == null) {
            throw new IllegalStateException("failed to persist AI report");
        }
        for (AiResearchResult.Citation citation : result.getCitations()) {
            Long evidenceId = evidenceIds.get(citation.getEvidenceRef());
            if (evidenceId == null || resultMapper.insertCitation(report.getId(), citation.getClaimId(),
                    evidenceId, citation.getSupportScore()) != 1) {
                throw new IllegalStateException("citation references unknown evidence");
            }
        }
        for (AiResearchResult.Usage usage : result.getUsage()) {
            if (resultMapper.insertUsage(result.getRunId(), usage) != 1) {
                throw new IllegalStateException("failed to persist model usage");
            }
        }
        if (resultMapper.markTaskWaitingReview(task.getId(), result.getRunId()) != 1
                || resultMapper.markRunWaitingReview(task.getId(), result.getRunId()) != 1) {
            throw new IllegalStateException("failed to finalize AI result state");
        }
    }

    @Transactional
    public void acknowledge(String eventId) {
        if (eventId != null && !eventId.isBlank()) {
            resultMapper.insertInbox(CONSUMER, eventId);
        }
    }

    private Map<String, Long> persistSources(AiTaskRecord task, List<AiResearchResult.Source> sources) {
        Map<String, Long> ids = new HashMap<>();
        for (AiResearchResult.Source source : sources) {
            AiSourceRecord record = new AiSourceRecord();
            record.setTaskId(task.getId());
            record.setRunId(task.getCurrentRunId());
            record.setUrl(source.getUrl());
            record.setTitle(source.getTitle() == null ? "" : source.getTitle());
            record.setContentHash(source.getContentHash());
            record.setRetrievedTime(OffsetDateTime.parse(source.getRetrievedAt()).toLocalDateTime());
            record.setMetadataJson(source.getMetadataJson());
            if (resultMapper.insertSource(record) != 1 || record.getId() == null) {
                throw new IllegalStateException("failed to persist source");
            }
            ids.put(source.getSourceRef(), record.getId());
        }
        return ids;
    }

    private Map<String, Long> persistEvidence(AiTaskRecord task,
                                               List<AiResearchResult.Evidence> evidence,
                                               Map<String, Long> sourceIds) {
        Map<String, Long> ids = new HashMap<>();
        for (AiResearchResult.Evidence item : evidence) {
            Long sourceId = sourceIds.get(item.getSourceRef());
            if (sourceId == null) {
                throw new IllegalStateException("evidence references unknown source");
            }
            AiEvidenceRecord record = new AiEvidenceRecord();
            record.setTaskId(task.getId());
            record.setRunId(task.getCurrentRunId());
            record.setSourceId(sourceId);
            record.setLocator(item.getLocator() == null ? "" : item.getLocator());
            record.setContent(item.getContent());
            record.setScore(item.getScore());
            if (resultMapper.insertEvidence(record) != 1 || record.getId() == null) {
                throw new IllegalStateException("failed to persist evidence");
            }
            ids.put(item.getEvidenceRef(), record.getId());
        }
        return ids;
    }

    private void validateEnvelope(String eventId, AiResearchResult result) {
        if (eventId == null || eventId.isBlank() || result == null || result.getTaskId() == null
                || result.getRunId() == null || result.getRunId().isBlank()) {
            throw new IllegalArgumentException("invalid AI result envelope");
        }
    }

    private void validatePayload(AiTaskRecord task, AiResearchResult result) {
        if (result.getTitle() == null || result.getTitle().isBlank() || result.getTitle().length() > 500
                || result.getContent() == null || result.getContent().isBlank()
                || result.getContent().length() > 200000
                || result.getQualityScore() == null || result.getQualityScore() < 0
                || result.getQualityScore() > 1 || result.getSources() == null
                || result.getEvidence() == null || result.getCitations() == null
                || result.getUsage() == null || result.getProvider() == null
                || result.getProvider().isBlank() || result.getProvider().length() > 64
                || result.getSources().isEmpty() || result.getSources().size() > task.getMaxSources()
                || result.getEvidence().size() > task.getMaxToolCalls() * 5
                // A dynamic Agent can make one planner call, up to one decision and one
                // search-model call per tool action, then one final decision and up to
                // two writer calls (single bounded revision).
                || result.getUsage().size() > task.getMaxToolCalls() * 2 + 4) {
            throw new IllegalArgumentException("AI result exceeds task bounds");
        }
        Set<String> sourceRefs = new HashSet<>();
        for (AiResearchResult.Source source : result.getSources()) {
            if (source.getSourceRef() == null || !sourceRefs.add(source.getSourceRef())
                    || source.getUrl() == null || source.getUrl().length() > 2048
                    || source.getContentHash() == null || !source.getContentHash().matches("[0-9a-fA-F]{64}")) {
                throw new IllegalArgumentException("invalid or duplicate source identity");
            }
            URI uri = URI.create(source.getUrl());
            if (!("https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme()))) {
                throw new IllegalArgumentException("source URL must use HTTP(S)");
            }
        }
        Set<String> evidenceRefs = new HashSet<>();
        for (AiResearchResult.Evidence item : result.getEvidence()) {
            if (item.getEvidenceRef() == null || !evidenceRefs.add(item.getEvidenceRef())
                    || !sourceRefs.contains(item.getSourceRef()) || item.getContent() == null
                    || item.getContent().isBlank() || item.getContent().length() > 50000
                    || item.getScore() == null || item.getScore() < 0 || item.getScore() > 1) {
                throw new IllegalArgumentException("invalid evidence identity or source binding");
            }
        }
        Set<String> claims = new HashSet<>();
        for (AiResearchResult.Citation citation : result.getCitations()) {
            if (citation.getClaimId() == null || citation.getClaimId().isBlank()
                    || !claims.add(citation.getClaimId())
                    || !evidenceRefs.contains(citation.getEvidenceRef())
                    || citation.getSupportScore() == null || citation.getSupportScore() < 0
                    || citation.getSupportScore() > 1) {
                throw new IllegalArgumentException("invalid citation binding");
            }
        }
        long totalOutputTokens = 0;
        for (AiResearchResult.Usage usage : result.getUsage()) {
            if (usage.getNodeName() == null || usage.getNodeName().isBlank()
                    || usage.getNodeName().length() > 64
                    || usage.getProvider() == null || usage.getProvider().isBlank()
                    || usage.getProvider().length() > 64
                    || usage.getModel() == null || usage.getModel().isBlank()
                    || usage.getModel().length() > 128
                    || usage.getInputTokens() == null || usage.getInputTokens() < 0
                    || usage.getOutputTokens() == null || usage.getOutputTokens() < 0
                    || usage.getEstimatedCost() == null
                    || usage.getEstimatedCost().compareTo(BigDecimal.ZERO) < 0
                    || usage.getLatencyMs() == null || usage.getLatencyMs() < 0
                    || usage.getRetryCount() == null || usage.getRetryCount() < 0
                    || usage.getRetryCount() > task.getMaxToolCalls()) {
                throw new IllegalArgumentException("invalid model usage");
            }
            totalOutputTokens += usage.getOutputTokens();
            if (totalOutputTokens > task.getMaxTokens()) {
                throw new IllegalArgumentException("model usage exceeds output token budget");
            }
        }
    }
}
