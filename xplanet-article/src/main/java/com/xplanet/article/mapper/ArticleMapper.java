package com.xplanet.article.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.xplanet.article.entity.Article;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface ArticleMapper extends BaseMapper<Article> {

    @Select("SELECT EXISTS(SELECT 1 FROM article WHERE id=#{articleId} AND deleted=0)")
    boolean existsActive(@Param("articleId") Long articleId);

    /**
     * 原子增减 likeCount,避免 select + update 竞态。
     * 同时返回受影响行数,可用于乐观地判断是否生效。
     */
    int incrLikeCount(@Param("articleId") Long articleId, @Param("delta") long delta);
}
