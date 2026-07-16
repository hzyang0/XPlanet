package com.xplanet.ai.persistence;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

@Mapper
public interface AiResultMapper {

    @Insert("INSERT IGNORE INTO consumer_inbox (consumer, event_id, processed_time) " +
            "VALUES (#{consumer}, #{eventId}, NOW())")
    int insertInbox(@Param("consumer") String consumer, @Param("eventId") String eventId);

    @Insert("INSERT INTO source_document " +
            "(task_id, run_id, url, title, content_hash, retrieved_time, metadata_json, create_time) " +
            "VALUES (#{taskId}, #{runId}, #{url}, #{title}, #{contentHash}, #{retrievedTime}, " +
            "#{metadataJson}, NOW())")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insertSource(AiSourceRecord source);

    @Insert("INSERT INTO evidence_chunk " +
            "(task_id, run_id, source_id, locator, content, score, create_time) " +
            "VALUES (#{taskId}, #{runId}, #{sourceId}, #{locator}, #{content}, #{score}, NOW())")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insertEvidence(AiEvidenceRecord evidence);

    @Insert("INSERT INTO ai_report " +
            "(task_id, run_id, version, status, title, content, quality_score, create_time, update_time) " +
            "VALUES (#{taskId}, #{runId}, 1, 'DRAFT', #{title}, #{content}, #{qualityScore}, NOW(), NOW())")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insertReport(AiReportRecord report);

    @Insert("INSERT INTO report_citation " +
            "(report_id, claim_id, evidence_id, support_score, create_time) " +
            "VALUES (#{reportId}, #{claimId}, #{evidenceId}, #{supportScore}, NOW())")
    int insertCitation(@Param("reportId") Long reportId,
                       @Param("claimId") String claimId,
                       @Param("evidenceId") Long evidenceId,
                       @Param("supportScore") Double supportScore);

    @Insert("INSERT INTO model_usage " +
            "(run_id, node_name, provider, model, input_tokens, output_tokens, estimated_cost, " +
            "latency_ms, retry_count, create_time) VALUES " +
            "(#{runId}, #{usage.nodeName}, #{usage.provider}, #{usage.model}, #{usage.inputTokens}, " +
            "#{usage.outputTokens}, #{usage.estimatedCost}, #{usage.latencyMs}, #{usage.retryCount}, NOW())")
    int insertUsage(@Param("runId") String runId,
                    @Param("usage") com.xplanet.api.dto.AiResearchResult.Usage usage);

    @Select("SELECT * FROM ai_report WHERE task_id=#{taskId} AND run_id=#{runId} LIMIT 1")
    AiReportRecord findByRun(@Param("taskId") Long taskId, @Param("runId") String runId);

    @Select("SELECT r.* FROM ai_report r JOIN ai_task t ON t.id=r.task_id " +
            "WHERE r.task_id=#{taskId} AND t.user_id=#{userId} ORDER BY r.version DESC LIMIT 1")
    AiReportRecord findOwnedReport(@Param("taskId") Long taskId, @Param("userId") Long userId);

    @Select("SELECT * FROM source_document WHERE task_id=#{taskId} AND run_id=#{runId} ORDER BY id")
    List<AiSourceRecord> listSources(@Param("taskId") Long taskId, @Param("runId") String runId);

    @Select("SELECT * FROM evidence_chunk WHERE task_id=#{taskId} AND run_id=#{runId} ORDER BY id")
    List<AiEvidenceRecord> listEvidence(@Param("taskId") Long taskId, @Param("runId") String runId);

    @Select("SELECT c.claim_id, c.evidence_id, c.support_score FROM report_citation c " +
            "WHERE c.report_id=#{reportId} ORDER BY c.claim_id, c.evidence_id")
    List<AiCitationRecord> listCitations(@Param("reportId") Long reportId);

    @Update("UPDATE ai_task SET status='WAITING_REVIEW', version=version+1, last_error=NULL, update_time=NOW() " +
            "WHERE id=#{taskId} AND current_run_id=#{runId} AND status='RUNNING'")
    int markTaskWaitingReview(@Param("taskId") Long taskId, @Param("runId") String runId);

    @Update("UPDATE ai_run SET status='WAITING_REVIEW', current_node='HUMAN_REVIEW', " +
            "finished_time=NOW(), update_time=NOW() WHERE run_id=#{runId} AND task_id=#{taskId}")
    int markRunWaitingReview(@Param("taskId") Long taskId, @Param("runId") String runId);

    @Update("UPDATE ai_run SET status='CANCELLED', current_node='CANCELLED', finished_time=NOW(), " +
            "update_time=NOW() WHERE run_id=#{runId} AND task_id=#{taskId}")
    int markRunCancelled(@Param("taskId") Long taskId, @Param("runId") String runId);

    @Update("UPDATE ai_report r JOIN ai_task t ON t.id=r.task_id " +
            "SET r.status='APPROVED', r.title=#{title}, r.content=#{content}, r.update_time=NOW(), " +
            "t.status='SUCCEEDED', t.version=t.version+1, t.update_time=NOW() " +
            "WHERE r.id=#{reportId} AND t.user_id=#{userId} " +
            "AND r.status IN ('DRAFT','APPROVED') AND t.status IN ('WAITING_REVIEW','SUCCEEDED')")
    int approve(@Param("reportId") Long reportId,
                @Param("userId") Long userId,
                @Param("title") String title,
                @Param("content") String content);

    @Update("UPDATE ai_report r JOIN ai_task t ON t.id=r.task_id " +
            "SET r.status='PUBLISHED', r.publish_article_id=#{articleId}, r.update_time=NOW() " +
            "WHERE r.id=#{reportId} AND t.user_id=#{userId} AND r.status IN ('APPROVED','PUBLISHED')")
    int markPublished(@Param("reportId") Long reportId,
                      @Param("userId") Long userId,
                      @Param("articleId") Long articleId);
}
