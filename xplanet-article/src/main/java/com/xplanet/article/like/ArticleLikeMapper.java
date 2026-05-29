package com.xplanet.article.like;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface ArticleLikeMapper extends BaseMapper<ArticleLike> {

    /**
     * 幂等 upsert 点赞明细。
     * 靠 (user_id, article_id) 唯一约束:首次插入,重复则更新 status。
     * 返回受影响行数:insert=1, update=2(MySQL), 无变化=0。
     * 用它判断是否"真实状态变化",决定要不要调整计数,从而实现可靠幂等。
     */
    @Update("INSERT INTO article_like (user_id, article_id, status, create_time, update_time) " +
            "VALUES (#{userId}, #{articleId}, #{status}, NOW(), NOW()) " +
            "ON DUPLICATE KEY UPDATE status = #{status}, update_time = NOW()")
    int upsertLike(@Param("userId") Long userId,
                   @Param("articleId") Long articleId,
                   @Param("status") int status);

    /** 查当前点赞状态(用于判断状态是否真的变化) */
    @org.apache.ibatis.annotations.Select(
            "SELECT status FROM article_like WHERE user_id=#{userId} AND article_id=#{articleId}")
    Integer selectStatus(@Param("userId") Long userId, @Param("articleId") Long articleId);
}
