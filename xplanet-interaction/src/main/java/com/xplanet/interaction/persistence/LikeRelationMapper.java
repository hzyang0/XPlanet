package com.xplanet.interaction.persistence;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface LikeRelationMapper {

    @Update("UPDATE like_relation SET status=1, update_time=NOW() " +
            "WHERE user_id=#{userId} AND article_id=#{articleId} AND status=0")
    int reactivate(@Param("userId") Long userId, @Param("articleId") Long articleId);

    @Insert("INSERT IGNORE INTO like_relation " +
            "(user_id, article_id, status, create_time, update_time) " +
            "VALUES (#{userId}, #{articleId}, 1, NOW(), NOW())")
    int insertLikedIfAbsent(@Param("userId") Long userId, @Param("articleId") Long articleId);

    @Update("UPDATE like_relation SET status=0, update_time=NOW() " +
            "WHERE user_id=#{userId} AND article_id=#{articleId} AND status=1")
    int cancelIfLiked(@Param("userId") Long userId, @Param("articleId") Long articleId);
}
