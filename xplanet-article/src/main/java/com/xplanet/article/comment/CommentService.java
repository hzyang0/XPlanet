package com.xplanet.article.comment;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.xplanet.api.request.CommentPublishRequest;
import com.xplanet.api.vo.CommentVO;
import com.xplanet.article.service.UserClient;
import com.xplanet.common.exception.BizException;
import com.xplanet.common.response.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 评论服务:发布评论 + 查询文章评论(组装成两级嵌套结构)。
 *
 * <h3>嵌套组装思路</h3>
 * <p>一次查出文章的所有评论(避免 N+1 查询),在内存里按 parentId 组装:
 * parentId=0 的是顶级评论,其余挂到对应父评论的 children 下。
 * 只做两级(评论 + 回复),不做无限层级——社区评论两级足够,无限嵌套体验也差。
 */
@Service
@RequiredArgsConstructor
public class CommentService {

    private final CommentMapper commentMapper;
    private final UserClient userClient;

    /** 发布评论 */
    public Long publish(Long userId, CommentPublishRequest req) {
        if (req.getContent() == null || req.getContent().trim().isEmpty()) {
            throw new BizException(ErrorCode.PARAM_INVALID);
        }
        Comment c = new Comment();
        c.setArticleId(req.getArticleId());
        c.setUserId(userId);
        c.setParentId(req.getParentId() == null ? 0L : req.getParentId());
        c.setContent(req.getContent().trim());
        c.setDeleted(0);
        c.setCreateTime(LocalDateTime.now());
        commentMapper.insert(c);
        return c.getId();
    }

    /** 查询某文章的评论,返回两级嵌套结构 */
    public List<CommentVO> listByArticle(Long articleId) {
        List<Comment> all = commentMapper.selectList(
                new LambdaQueryWrapper<Comment>()
                        .eq(Comment::getArticleId, articleId)
                        .eq(Comment::getDeleted, 0)
                        .orderByAsc(Comment::getCreateTime));

        // 转 VO 并填充用户名
        Map<Long, CommentVO> idToVo = new LinkedHashMap<>();
        for (Comment c : all) {
            CommentVO vo = new CommentVO();
            vo.setId(c.getId());
            vo.setArticleId(c.getArticleId());
            vo.setUserId(c.getUserId());
            vo.setUserName(userClient.getUserName(c.getUserId()));
            vo.setParentId(c.getParentId());
            vo.setContent(c.getContent());
            vo.setCreateTime(c.getCreateTime());
            idToVo.put(c.getId(), vo);
        }

        // 组装两级:顶级评论进结果,回复挂到父评论 children
        List<CommentVO> roots = new ArrayList<>();
        for (CommentVO vo : idToVo.values()) {
            if (vo.getParentId() == null || vo.getParentId() == 0L) {
                roots.add(vo);
            } else {
                CommentVO parent = idToVo.get(vo.getParentId());
                if (parent != null) {
                    parent.getChildren().add(vo);
                } else {
                    // 父评论可能被删,降级为顶级展示
                    roots.add(vo);
                }
            }
        }
        return roots;
    }
}
