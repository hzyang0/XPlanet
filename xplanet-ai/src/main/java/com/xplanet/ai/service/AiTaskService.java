package com.xplanet.ai.service;

import com.xplanet.ai.domain.AiTaskStatus;
import com.xplanet.ai.outbox.AiOutboxMapper;
import com.xplanet.ai.persistence.AiTaskMapper;
import com.xplanet.ai.persistence.AiTaskRecord;
import com.xplanet.api.dto.AiTaskCommand;
import com.xplanet.api.request.CreateResearchTaskRequest;
import com.xplanet.api.vo.AiTaskVO;
import com.xplanet.common.exception.BizException;
import com.xplanet.common.response.ErrorCode;
import com.xplanet.common.util.JsonUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class AiTaskService {

    private static final String EVENT_REQUESTED = "AI_TASK_REQUESTED";
    private static final String EVENT_CANCELLED = "AI_TASK_CANCELLED";

    private final AiTaskMapper taskMapper;
    private final AiOutboxMapper outboxMapper;

    /**
     * Creates the task, first run and command Outbox atomically.
     * The user-scoped idempotency key prevents browser retries from creating duplicate research jobs.
     */
    @Transactional
    public AiTaskVO create(Long userId, String idempotencyKey, CreateResearchTaskRequest request) {
        validateIdentity(userId);
        String key = normalizeKey(idempotencyKey);
        NormalizedRequest normalized = normalize(request);

        AiTaskRecord existing = taskMapper.findByIdempotencyKey(userId, key);
        if (existing != null) {
            assertSameRequest(existing, normalized);
            return existing.toView();
        }

        String runId = UUID.randomUUID().toString();
        AiTaskRecord task = new AiTaskRecord();
        task.setUserId(userId);
        task.setIdempotencyKey(key);
        task.setQuestion(normalized.question);
        task.setStatus(AiTaskStatus.QUEUED.name());
        task.setCurrentRunId(runId);
        task.setMaxSources(normalized.maxSources);
        task.setMaxToolCalls(normalized.maxToolCalls);
        task.setMaxTokens(normalized.maxTokens);
        task.setDeadlineSeconds(normalized.deadlineSeconds);

        if (taskMapper.insertTask(task) == 0) {
            existing = taskMapper.findByIdempotencyKey(userId, key);
            if (existing == null) {
                throw new IllegalStateException("idempotent task insert lost without existing row");
            }
            assertSameRequest(existing, normalized);
            return existing.toView();
        }
        if (task.getId() == null || taskMapper.insertRun(runId, task.getId()) != 1) {
            throw new IllegalStateException("failed to persist AI task run");
        }

        appendEvent(task, EVENT_REQUESTED, 0);
        task.setVersion(0);
        return task.toView();
    }

    @Transactional(readOnly = true)
    public AiTaskVO get(Long userId, Long taskId) {
        return requireOwned(userId, taskId).toView();
    }

    @Transactional(readOnly = true)
    public List<AiTaskVO> list(Long userId, int limit) {
        validateIdentity(userId);
        int boundedLimit = Math.max(1, Math.min(limit, 100));
        return taskMapper.listOwned(userId, boundedLimit).stream()
                .map(AiTaskRecord::toView)
                .collect(Collectors.toList());
    }

    @Transactional
    public AiTaskVO cancel(Long userId, Long taskId) {
        AiTaskRecord task = requireOwned(userId, taskId);
        AiTaskStatus status = AiTaskStatus.valueOf(task.getStatus());
        if (status == AiTaskStatus.CANCELLED) {
            return task.toView();
        }
        if (status.isTerminal()) {
            throw new BizException(ErrorCode.AI_TASK_STATE_CONFLICT);
        }
        if (taskMapper.cancel(taskId, userId, task.getVersion()) != 1) {
            AiTaskRecord latest = requireOwned(userId, taskId);
            if (AiTaskStatus.CANCELLED.name().equals(latest.getStatus())) {
                return latest.toView();
            }
            throw new BizException(ErrorCode.AI_TASK_STATE_CONFLICT);
        }

        task.setStatus(AiTaskStatus.CANCELLED.name());
        task.setVersion(task.getVersion() + 1);
        appendEvent(task, EVENT_CANCELLED, task.getVersion());
        return task.toView();
    }

    private AiTaskRecord requireOwned(Long userId, Long taskId) {
        validateIdentity(userId);
        if (taskId == null || taskId <= 0) {
            throw new BizException(ErrorCode.PARAM_INVALID);
        }
        AiTaskRecord task = taskMapper.findOwned(taskId, userId);
        if (task == null) {
            // Deliberately do not distinguish missing from another user's task.
            throw new BizException(ErrorCode.AI_TASK_NOT_FOUND);
        }
        return task;
    }

    private void appendEvent(AiTaskRecord task, String eventType, int aggregateVersion) {
        String eventId = UUID.randomUUID().toString();
        AiTaskCommand command = AiTaskCommand.builder()
                .eventId(eventId)
                .eventType(eventType)
                .schemaVersion(1)
                .taskId(task.getId())
                .runId(task.getCurrentRunId())
                .aggregateVersion(aggregateVersion)
                .occurredAt(System.currentTimeMillis())
                .userId(task.getUserId())
                .question(task.getQuestion())
                .maxSources(task.getMaxSources())
                .maxToolCalls(task.getMaxToolCalls())
                .maxTokens(task.getMaxTokens())
                .deadlineSeconds(task.getDeadlineSeconds())
                .build();
        int inserted = outboxMapper.insertEvent(eventId, task.getId(), task.getCurrentRunId(),
                eventType, aggregateVersion, JsonUtil.toJson(command));
        if (inserted != 1) {
            throw new IllegalStateException("failed to persist AI outbox event");
        }
    }

    private void validateIdentity(Long userId) {
        if (userId == null || userId <= 0) {
            throw new BizException(ErrorCode.USER_NOT_LOGIN);
        }
    }

    private String normalizeKey(String key) {
        if (key == null || key.isBlank() || key.trim().length() > 128) {
            throw new BizException(ErrorCode.PARAM_INVALID.getCode(), "Idempotency-Key 必填且不能超过128字符");
        }
        return key.trim();
    }

    private NormalizedRequest normalize(CreateResearchTaskRequest request) {
        if (request == null || request.getQuestion() == null || request.getQuestion().isBlank()) {
            throw new BizException(ErrorCode.PARAM_INVALID);
        }
        String question = request.getQuestion().trim();
        if (question.length() > 2000) {
            throw new BizException(ErrorCode.PARAM_INVALID);
        }
        NormalizedRequest normalized = new NormalizedRequest(question,
                request.getMaxSources() == null ? 5 : request.getMaxSources(),
                request.getMaxToolCalls() == null ? 10 : request.getMaxToolCalls(),
                request.getMaxTokens() == null ? 8000 : request.getMaxTokens(),
                request.getDeadlineSeconds() == null ? 300 : request.getDeadlineSeconds());
        if (normalized.maxSources < 1 || normalized.maxSources > 20
                || normalized.maxToolCalls < 1 || normalized.maxToolCalls > 50
                || normalized.maxTokens < 1000 || normalized.maxTokens > 100000
                || normalized.deadlineSeconds < 30 || normalized.deadlineSeconds > 3600) {
            throw new BizException(ErrorCode.PARAM_INVALID);
        }
        return normalized;
    }

    private void assertSameRequest(AiTaskRecord task, NormalizedRequest request) {
        if (!Objects.equals(task.getQuestion(), request.question)
                || !Objects.equals(task.getMaxSources(), request.maxSources)
                || !Objects.equals(task.getMaxToolCalls(), request.maxToolCalls)
                || !Objects.equals(task.getMaxTokens(), request.maxTokens)
                || !Objects.equals(task.getDeadlineSeconds(), request.deadlineSeconds)) {
            throw new BizException(ErrorCode.AI_IDEMPOTENCY_CONFLICT);
        }
    }

    private static class NormalizedRequest {
        private final String question;
        private final Integer maxSources;
        private final Integer maxToolCalls;
        private final Integer maxTokens;
        private final Integer deadlineSeconds;

        private NormalizedRequest(String question, Integer maxSources, Integer maxToolCalls,
                                  Integer maxTokens, Integer deadlineSeconds) {
            this.question = question;
            this.maxSources = maxSources;
            this.maxToolCalls = maxToolCalls;
            this.maxTokens = maxTokens;
            this.deadlineSeconds = deadlineSeconds;
        }
    }
}
