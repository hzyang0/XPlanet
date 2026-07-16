package com.xplanet.ai.persistence;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

@Mapper
public interface AiTaskMapper {

    @Insert("INSERT IGNORE INTO ai_task " +
            "(user_id, idempotency_key, question, status, current_run_id, version, " +
            "max_sources, max_tool_calls, max_tokens, deadline_seconds, create_time, update_time) " +
            "VALUES (#{userId}, #{idempotencyKey}, #{question}, #{status}, #{currentRunId}, 0, " +
            "#{maxSources}, #{maxToolCalls}, #{maxTokens}, #{deadlineSeconds}, NOW(), NOW())")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insertTask(AiTaskRecord task);

    @Insert("INSERT INTO ai_run " +
            "(run_id, task_id, status, current_node, attempt, create_time, update_time) " +
            "VALUES (#{runId}, #{taskId}, 'QUEUED', 'VALIDATE_INPUT', 1, NOW(), NOW())")
    int insertRun(@Param("runId") String runId, @Param("taskId") Long taskId);

    @Select("SELECT * FROM ai_task WHERE user_id=#{userId} AND idempotency_key=#{key}")
    AiTaskRecord findByIdempotencyKey(@Param("userId") Long userId, @Param("key") String key);

    @Select("SELECT * FROM ai_task WHERE id=#{taskId} AND user_id=#{userId}")
    AiTaskRecord findOwned(@Param("taskId") Long taskId, @Param("userId") Long userId);

    @Select("SELECT * FROM ai_task WHERE user_id=#{userId} ORDER BY id DESC LIMIT #{limit}")
    List<AiTaskRecord> listOwned(@Param("userId") Long userId, @Param("limit") int limit);

    @Update("UPDATE ai_task SET status='CANCELLED', version=version+1, update_time=NOW() " +
            "WHERE id=#{taskId} AND user_id=#{userId} AND version=#{version} " +
            "AND status IN ('QUEUED','RUNNING','RETRYING','WAITING_REVIEW')")
    int cancel(@Param("taskId") Long taskId,
               @Param("userId") Long userId,
               @Param("version") Integer version);
}
