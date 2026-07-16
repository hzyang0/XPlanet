package com.xplanet.article.ai;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface AiPublishedArticleMapper {

    @Select("SELECT article_id FROM ai_published_article WHERE report_id=#{reportId}")
    Long findArticleId(@Param("reportId") Long reportId);

    @Insert("INSERT IGNORE INTO ai_published_article (report_id, article_id, create_time) " +
            "VALUES (#{reportId}, #{articleId}, NOW())")
    int insert(@Param("reportId") Long reportId, @Param("articleId") Long articleId);
}
