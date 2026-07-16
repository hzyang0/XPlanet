package com.xplanet.ai.persistence;

import com.xplanet.api.dto.AiCheckpointData;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface AiRunStepMapper {

    @Insert("INSERT INTO ai_run_step " +
            "(run_id,node_name,input_hash,state_version,status,checkpoint_json,duration_ms,create_time,update_time) " +
            "VALUES (#{runId},#{node},#{inputHash},#{stateVersion},'COMPLETED',#{stateJson},#{durationMs},NOW(),NOW()) " +
            "ON DUPLICATE KEY UPDATE state_version=VALUES(state_version), status='COMPLETED', " +
            "checkpoint_json=VALUES(checkpoint_json), duration_ms=VALUES(duration_ms), " +
            "error_code=NULL, error_message=NULL, update_time=NOW()")
    int upsert(AiCheckpointData checkpoint);

    @Select("SELECT id,run_id,node_name,input_hash,state_version,checkpoint_json,duration_ms " +
            "FROM ai_run_step WHERE run_id=#{runId} AND status='COMPLETED' " +
            "AND checkpoint_json IS NOT NULL ORDER BY id DESC LIMIT 1")
    AiRunStepRecord findLatest(@Param("runId") String runId);

    @Update("UPDATE ai_run SET current_node=#{node}, update_time=NOW() " +
            "WHERE run_id=#{runId} AND task_id=#{taskId} AND status IN ('RUNNING','RETRYING')")
    int updateCurrentNode(@Param("taskId") Long taskId,
                          @Param("runId") String runId,
                          @Param("node") String node);
}
