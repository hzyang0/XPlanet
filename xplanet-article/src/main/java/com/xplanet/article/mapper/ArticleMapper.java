package com.xplanet.article.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.xplanet.article.entity.Article;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface ArticleMapper extends BaseMapper<Article> {

    /**
     * 原子增减 likeCount,避免 select + update 竞态。
     * 同时返回受影响行数,可用于乐观地判断是否生效。
     */
    int incrLikeCount(@Param("articleId") Long articleId, @Param("delta") long delta);
}
