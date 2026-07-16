package com.xplanet.interaction.persistence;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

@Mapper
public interface LikeOutboxMapper {

    @Insert("INSERT INTO like_outbox " +
            "(event_id, user_id, article_id, delta, status, retry_count, next_retry_time, create_time) " +
            "VALUES (#{eventId}, #{userId}, #{articleId}, #{delta}, 0, 0, NOW(), NOW())")
    int insertEvent(@Param("eventId") String eventId,
                    @Param("userId") Long userId,
                    @Param("articleId") Long articleId,
                    @Param("delta") int delta);

    @Select("SELECT id, event_id, user_id, article_id, delta, retry_count " +
            "FROM like_outbox " +
            "WHERE (status=0 AND next_retry_time<=NOW()) " +
            "   OR (status=1 AND locked_until<=NOW()) " +
            "ORDER BY id LIMIT #{limit}")
    List<LikeOutboxEvent> findPublishable(@Param("limit") int limit);

    @Update("UPDATE like_outbox SET status=1, locked_by=#{owner}, " +
            "locked_until=TIMESTAMPADD(SECOND, #{leaseSeconds}, NOW()) " +
            "WHERE id=#{id} AND ((status=0 AND next_retry_time<=NOW()) " +
            "OR (status=1 AND locked_until<=NOW()))")
    int claim(@Param("id") Long id,
              @Param("owner") String owner,
              @Param("leaseSeconds") int leaseSeconds);

    @Update("UPDATE like_outbox SET status=2, sent_time=NOW(), locked_by=NULL, locked_until=NULL " +
            "WHERE id=#{id} AND status=1 AND locked_by=#{owner}")
    int markSent(@Param("id") Long id, @Param("owner") String owner);

    @Update("UPDATE like_outbox SET status=0, retry_count=retry_count+1, " +
            "next_retry_time=TIMESTAMPADD(SECOND, #{delaySeconds}, NOW()), " +
            "last_error=#{error}, locked_by=NULL, locked_until=NULL " +
            "WHERE id=#{id} AND status=1 AND locked_by=#{owner}")
    int releaseForRetry(@Param("id") Long id,
                        @Param("owner") String owner,
                        @Param("delaySeconds") int delaySeconds,
                        @Param("error") String error);
}
