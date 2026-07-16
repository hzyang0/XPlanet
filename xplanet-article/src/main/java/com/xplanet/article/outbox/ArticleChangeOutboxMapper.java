package com.xplanet.article.outbox;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

@Mapper
public interface ArticleChangeOutboxMapper {

    @Insert("INSERT INTO article_change_outbox " +
            "(event_id, article_id, operation, status, retry_count, next_retry_time, create_time) " +
            "VALUES (#{eventId}, #{articleId}, #{operation}, 0, 0, " +
            "TIMESTAMPADD(SECOND, #{delaySeconds}, NOW()), NOW())")
    int insertEvent(@Param("eventId") String eventId,
                    @Param("articleId") Long articleId,
                    @Param("operation") String operation,
                    @Param("delaySeconds") int delaySeconds);

    @Select("SELECT id, event_id, article_id, operation, retry_count " +
            "FROM article_change_outbox " +
            "WHERE (status=0 AND next_retry_time<=NOW()) " +
            "   OR (status=1 AND locked_until<=NOW()) " +
            "ORDER BY id LIMIT #{limit}")
    List<ArticleChangeOutboxEvent> findPublishable(@Param("limit") int limit);

    @Update("UPDATE article_change_outbox SET status=1, locked_by=#{owner}, " +
            "locked_until=TIMESTAMPADD(SECOND, #{leaseSeconds}, NOW()) " +
            "WHERE id=#{id} AND ((status=0 AND next_retry_time<=NOW()) " +
            "OR (status=1 AND locked_until<=NOW()))")
    int claim(@Param("id") Long id,
              @Param("owner") String owner,
              @Param("leaseSeconds") int leaseSeconds);

    @Update("UPDATE article_change_outbox SET status=2, sent_time=NOW(), " +
            "locked_by=NULL, locked_until=NULL " +
            "WHERE id=#{id} AND status=1 AND locked_by=#{owner}")
    int markSent(@Param("id") Long id, @Param("owner") String owner);

    @Update("UPDATE article_change_outbox SET status=0, retry_count=retry_count+1, " +
            "next_retry_time=TIMESTAMPADD(SECOND, #{delaySeconds}, NOW()), " +
            "last_error=#{error}, locked_by=NULL, locked_until=NULL " +
            "WHERE id=#{id} AND status=1 AND locked_by=#{owner}")
    int releaseForRetry(@Param("id") Long id,
                        @Param("owner") String owner,
                        @Param("delaySeconds") int delaySeconds,
                        @Param("error") String error);
}
