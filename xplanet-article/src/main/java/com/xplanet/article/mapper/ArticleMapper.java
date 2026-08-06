package com.xplanet.article.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.xplanet.article.entity.Article;
import com.xplanet.api.vo.InternalArticleSearchVO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface ArticleMapper extends BaseMapper<Article> {

    @Select("SELECT EXISTS(SELECT 1 FROM article WHERE id=#{articleId} AND deleted=0)")
    boolean existsActive(@Param("articleId") Long articleId);

    @Select("SELECT id AS article_id, title, LEFT(content, 4000) AS content, " +
            "MATCH(title, content) AGAINST (#{query} IN NATURAL LANGUAGE MODE) AS score, " +
            "like_count, update_time FROM article " +
            "WHERE deleted=0 " +
            "AND FIND_IN_SET('ai', REPLACE(COALESCE(tags, ''), ' ', '')) = 0 " +
            "AND MATCH(title, content) AGAINST (#{query} IN NATURAL LANGUAGE MODE) > 0 " +
            "ORDER BY score DESC, like_count DESC, id DESC LIMIT #{topK}")
    List<InternalArticleSearchVO> searchKnowledge(@Param("query") String query,
                                                   @Param("topK") int topK);

    /**
     * 原子增减 likeCount,避免 select + update 竞态。
     * 同时返回受影响行数,可用于乐观地判断是否生效。
     */
    int incrLikeCount(@Param("articleId") Long articleId, @Param("delta") long delta);
}
