package com.xplanet.ai.outbox;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

@Mapper
public interface AiOutboxMapper {

    @Insert("INSERT INTO ai_outbox " +
            "(event_id, aggregate_id, run_id, event_type, aggregate_version, payload, status, " +
            "retry_count, next_retry_time, create_time) " +
            "VALUES (#{eventId}, #{taskId}, #{runId}, #{eventType}, #{aggregateVersion}, #{payload}, " +
            "0, 0, NOW(), NOW())")
    int insertEvent(@Param("eventId") String eventId,
                    @Param("taskId") Long taskId,
                    @Param("runId") String runId,
                    @Param("eventType") String eventType,
                    @Param("aggregateVersion") Integer aggregateVersion,
                    @Param("payload") String payload);

    @Select("SELECT id, event_id, event_type, payload, retry_count FROM ai_outbox " +
            "WHERE (status=0 AND next_retry_time<=NOW()) OR (status=1 AND locked_until<=NOW()) " +
            "ORDER BY id LIMIT #{limit}")
    List<AiOutboxEvent> findPublishable(@Param("limit") int limit);

    @Update("UPDATE ai_outbox SET status=1, locked_by=#{owner}, " +
            "locked_until=TIMESTAMPADD(SECOND, #{leaseSeconds}, NOW()) " +
            "WHERE id=#{id} AND ((status=0 AND next_retry_time<=NOW()) " +
            "OR (status=1 AND locked_until<=NOW()))")
    int claim(@Param("id") Long id,
              @Param("owner") String owner,
              @Param("leaseSeconds") int leaseSeconds);

    @Update("UPDATE ai_outbox SET status=2, sent_time=NOW(), locked_by=NULL, locked_until=NULL " +
            "WHERE id=#{id} AND status=1 AND locked_by=#{owner}")
    int markSent(@Param("id") Long id, @Param("owner") String owner);

    @Update("UPDATE ai_outbox SET status=0, retry_count=retry_count+1, " +
            "next_retry_time=TIMESTAMPADD(SECOND, #{delaySeconds}, NOW()), last_error=#{error}, " +
            "locked_by=NULL, locked_until=NULL WHERE id=#{id} AND status=1 AND locked_by=#{owner}")
    int releaseForRetry(@Param("id") Long id,
                        @Param("owner") String owner,
                        @Param("delaySeconds") int delaySeconds,
                        @Param("error") String error);
}
