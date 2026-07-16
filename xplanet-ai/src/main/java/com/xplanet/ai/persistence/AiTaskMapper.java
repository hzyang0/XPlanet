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

    @Select("SELECT * FROM ai_task WHERE id=#{taskId}")
    AiTaskRecord findInternal(@Param("taskId") Long taskId);

    @Select("SELECT * FROM ai_task WHERE id=#{taskId} FOR UPDATE")
    AiTaskRecord findInternalForUpdate(@Param("taskId") Long taskId);

    @Select("SELECT * FROM ai_task WHERE user_id=#{userId} ORDER BY id DESC LIMIT #{limit}")
    List<AiTaskRecord> listOwned(@Param("userId") Long userId, @Param("limit") int limit);

    @Update("UPDATE ai_task SET status='CANCELLED', version=version+1, update_time=NOW() " +
            "WHERE id=#{taskId} AND user_id=#{userId} AND version=#{version} " +
            "AND status IN ('QUEUED','RUNNING','RETRYING','WAITING_REVIEW')")
    int cancel(@Param("taskId") Long taskId,
               @Param("userId") Long userId,
               @Param("version") Integer version);

    @Update("UPDATE ai_task SET status='RUNNING', version=version+1, last_error=NULL, update_time=NOW() " +
            "WHERE id=#{taskId} AND current_run_id=#{runId} AND status IN ('QUEUED','RETRYING')")
    int markRunning(@Param("taskId") Long taskId, @Param("runId") String runId);

    @Update("UPDATE ai_run SET status='RUNNING', current_node='VALIDATE_INPUT', " +
            "started_time=COALESCE(started_time,NOW()), update_time=NOW() " +
            "WHERE run_id=#{runId} AND task_id=#{taskId} AND status IN ('QUEUED','RETRYING','RUNNING')")
    int markRunRunning(@Param("taskId") Long taskId, @Param("runId") String runId);

    @Update("UPDATE ai_task SET status='RETRYING', version=version+1, last_error=#{error}, update_time=NOW() " +
            "WHERE id=#{taskId} AND current_run_id=#{runId} AND status='RUNNING'")
    int markRetrying(@Param("taskId") Long taskId,
                     @Param("runId") String runId,
                     @Param("error") String error);

    @Update("UPDATE ai_run SET status='RETRYING', last_error=#{error}, update_time=NOW() " +
            "WHERE run_id=#{runId} AND task_id=#{taskId} AND status='RUNNING'")
    int markRunRetrying(@Param("taskId") Long taskId,
                        @Param("runId") String runId,
                        @Param("error") String error);
}
