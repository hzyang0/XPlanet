package com.xplanet.article.projection;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

@Mapper
public interface LikeCountDeltaMapper {

    @Insert("INSERT IGNORE INTO like_count_delta " +
            "(event_id, article_id, delta, status, create_time) " +
            "VALUES (#{eventId}, #{articleId}, #{delta}, 0, NOW())")
    int insertIgnore(@Param("eventId") String eventId,
                     @Param("articleId") Long articleId,
                     @Param("delta") long delta);

    @Select("SELECT id, event_id, article_id, delta FROM like_count_delta " +
            "WHERE status=0 ORDER BY id LIMIT #{limit} FOR UPDATE SKIP LOCKED")
    List<LikeCountDelta> lockPending(@Param("limit") int limit);

    @Update({"<script>",
            "UPDATE like_count_delta SET status=1, applied_time=NOW() ",
            "WHERE status=0 AND id IN ",
            "<foreach collection='ids' item='id' open='(' separator=',' close=')'>#{id}</foreach>",
            "</script>"})
    int markApplied(@Param("ids") List<Long> ids);

    @Update({"<script>",
            "UPDATE like_count_delta SET status=2, applied_time=NOW(), error=#{error} ",
            "WHERE status=0 AND id IN ",
            "<foreach collection='ids' item='id' open='(' separator=',' close=')'>#{id}</foreach>",
            "</script>"})
    int markRejected(@Param("ids") List<Long> ids, @Param("error") String error);
}
