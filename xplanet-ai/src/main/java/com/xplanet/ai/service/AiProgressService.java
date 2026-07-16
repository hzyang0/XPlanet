package com.xplanet.ai.service;

import com.xplanet.ai.persistence.AiTaskMapper;
import com.xplanet.ai.persistence.AiTaskRecord;
import com.xplanet.api.dto.AiProgressEvent;
import com.xplanet.common.exception.BizException;
import com.xplanet.common.response.ErrorCode;
import com.xplanet.common.util.JsonUtil;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.connection.stream.StreamReadOptions;
import org.springframework.data.redis.connection.stream.StreamRecords;
import org.springframework.data.redis.connection.stream.StringRecord;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;

@Service
public class AiProgressService {

    private final StringRedisTemplate redisTemplate;
    private final AiTaskMapper taskMapper;
    private final Executor executor;

    public AiProgressService(StringRedisTemplate redisTemplate,
                             AiTaskMapper taskMapper,
                             @Qualifier("aiProgressExecutor") Executor executor) {
        this.redisTemplate = redisTemplate;
        this.taskMapper = taskMapper;
        this.executor = executor;
    }

    @Value("${ai.progress.max-length:1000}")
    private long maxLength;

    @Value("${ai.progress.ttl-hours:24}")
    private long ttlHours;

    public void append(Long taskId, AiProgressEvent event) {
        AiTaskRecord task = taskMapper.findInternal(taskId);
        if (task == null || !task.getCurrentRunId().equals(event.getRunId())) {
            throw new IllegalArgumentException("progress does not match task run");
        }
        if (event.getTimestamp() == null) {
            event.setTimestamp(System.currentTimeMillis());
        }
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("runId", event.getRunId());
        fields.put("node", event.getNode());
        fields.put("status", event.getStatus());
        fields.put("message", event.getMessage() == null ? "" : event.getMessage());
        fields.put("progress", String.valueOf(event.getProgress() == null ? 0 : event.getProgress()));
        fields.put("timestamp", String.valueOf(event.getTimestamp()));
        String key = streamKey(taskId);
        StringRecord record = StreamRecords.string(fields).withStreamKey(key);
        redisTemplate.opsForStream().add(record);
        redisTemplate.opsForStream().trim(key, maxLength, true);
        redisTemplate.expire(key, Duration.ofHours(ttlHours));
    }

    public SseEmitter subscribe(Long userId, Long taskId, String lastEventId) {
        AiTaskRecord task = taskMapper.findOwned(taskId, userId);
        if (task == null) {
            throw new BizException(ErrorCode.AI_TASK_NOT_FOUND);
        }
        SseEmitter emitter = new SseEmitter(35_000L);
        String offset = lastEventId == null || lastEventId.isBlank() ? "0-0" : lastEventId;
        executor.execute(() -> stream(taskId, offset, emitter));
        return emitter;
    }

    public boolean isCancelled(Long taskId) {
        AiTaskRecord task = taskMapper.findInternal(taskId);
        return task == null || "CANCELLED".equals(task.getStatus());
    }

    @SuppressWarnings("unchecked")
    private void stream(Long taskId, String initialOffset, SseEmitter emitter) {
        String key = streamKey(taskId);
        String offset = initialOffset;
        long deadline = System.currentTimeMillis() + 30_000L;
        try {
            while (System.currentTimeMillis() < deadline) {
                List<MapRecord<String, Object, Object>> records = redisTemplate.opsForStream().read(
                        StreamReadOptions.empty().count(100).block(Duration.ofSeconds(2)),
                        StreamOffset.create(key, ReadOffset.from(offset)));
                if (records == null || records.isEmpty()) {
                    continue;
                }
                for (MapRecord<String, Object, Object> record : records) {
                    offset = record.getId().getValue();
                    emitter.send(SseEmitter.event()
                            .id(offset)
                            .name("progress")
                            .data(JsonUtil.toJson(record.getValue())));
                }
            }
            emitter.send(SseEmitter.event().name("heartbeat").data("{}"));
            emitter.complete();
        } catch (Exception e) {
            emitter.completeWithError(e);
        }
    }

    private String streamKey(Long taskId) {
        return "xp:ai:task:" + taskId + ":events";
    }
}
