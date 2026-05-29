package com.xplanet.article.hot;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.xplanet.api.vo.ArticleListItemVO;
import com.xplanet.article.entity.Article;
import com.xplanet.article.mapper.ArticleMapper;
import com.xplanet.article.service.UserClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * 热门榜单服务。
 *
 * <h3>设计要点</h3>
 * <ul>
 *   <li><b>存储</b>:Redis ZSet,score=热度分,member=articleId。
 *       ZSet 天然按 score 有序,O(log N) 取 Top N,完美匹配排行榜场景。</li>
 *   <li><b>热度算法</b>:简化版 = likeCount * 2 + viewCount + 时间衰减。
 *       让新文章有出头机会,避免老文章长期霸榜。生产可加入评论数、转发数等。</li>
 *   <li><b>更新时机</b>:定时每分钟刷一次,从 DB 全量重算后 INTO ZSet。
 *       <b>不在每次点赞时实时更新</b>——理由:
 *       (1) 点赞链路已经在削峰,再实时更新热榜会破坏削峰效果;
 *       (2) 排行榜对实时性要求不高,分钟级足够;
 *       (3) 定时全量重算逻辑简单,可靠性强。
 *       这是「实时性 vs 性能/简洁」的典型权衡。</li>
 *   <li><b>降级</b>:Redis ZSet 为空(冷启动还没刷过)时,直接查 DB 兜底。</li>
 * </ul>
 *
 * <h3>面试讲点</h3>
 * <p>1. Redis ZSet 实现排行榜是经典用法,会被问"为什么不用 List/Set":
 *      List 不能按分值排序,Set 没有分值。ZSet = Set + score,完美契合。<br>
 *    2. 排行榜的实时性 vs 性能权衡——一定要能说出"为什么不实时更新"。<br>
 *    3. 复用 Redis 没引入新中间件——保持了"按需选型"的一致立场。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class HotRankService {

    private final ArticleMapper articleMapper;
    private final StringRedisTemplate redis;
    private final UserClient userClient;

    /** Redis ZSet key:全局热门榜 */
    private static final String HOT_KEY = "xp:hot:articles";
    /** ZSet 容量上限(只保留 Top N) */
    private static final int CAPACITY = 100;
    /** 默认返回的榜单大小 */
    private static final int DEFAULT_LIMIT = 10;

    /**
     * 查询热门 Top N。Redis ZSet 优先,空时降级查 DB。
     */
    public List<ArticleListItemVO> getTopN(int limit) {
        if (limit <= 0 || limit > CAPACITY) limit = DEFAULT_LIMIT;

        // 从 ZSet 倒序取 Top N(score 高的在前)
        Set<String> ids = redis.opsForZSet().reverseRange(HOT_KEY, 0, limit - 1);
        if (ids == null || ids.isEmpty()) {
            // 降级:ZSet 没数据,直接查 DB 按 likeCount 排
            log.warn("hot rank ZSet empty, fallback to DB");
            return fallbackFromDb(limit);
        }

        // 批量查文章(注意:这里没保证 ZSet 顺序,要手动按 ids 顺序排回去)
        List<Long> idList = ids.stream().map(Long::valueOf).collect(Collectors.toList());
        List<Article> articles = articleMapper.selectBatchIds(idList);

        // 按 idList 顺序排序文章(MySQL 返回顺序不保证)
        Map<Long, Article> map = articles.stream()
                .collect(Collectors.toMap(Article::getId, a -> a));
        return idList.stream()
                .map(map::get)
                .filter(a -> a != null && a.getDeleted() == 0)
                .map(this::toVO)
                .collect(Collectors.toList());
    }

    /**
     * 定时刷新热门榜:从 DB 全量重算,覆盖 Redis ZSet。
     * 每 60 秒执行一次。
     */
    @Scheduled(fixedDelay = 60_000, initialDelay = 5_000)
    public void refresh() {
        try {
            // 取所有未删除文章(实际场景应分页,这里 demo 简化)
            List<Article> all = articleMapper.selectList(
                    new LambdaQueryWrapper<Article>().eq(Article::getDeleted, 0));
            if (all.isEmpty()) return;

            // 批量准备 ZSet 数据
            Set<ZSetOperations.TypedTuple<String>> tuples = all.stream()
                    .map(a -> ZSetOperations.TypedTuple.of(
                            String.valueOf(a.getId()),
                            calcScore(a)))
                    .collect(Collectors.toSet());

            // 用临时 key 写入,再 rename 原子替换,避免刷榜过程中查询读到不一致数据
            String tmpKey = HOT_KEY + ":refreshing";
            redis.delete(tmpKey);
            redis.opsForZSet().add(tmpKey, tuples);

            // 只保留 Top CAPACITY(按 score 升序删除,保留 score 高的)
            Long size = redis.opsForZSet().size(tmpKey);
            if (size != null && size > CAPACITY) {
                redis.opsForZSet().removeRange(tmpKey, 0, size - CAPACITY - 1);
            }

            // 设过期时间作为兜底(即使定时任务挂了,数据也不会无限有效)
            redis.expire(tmpKey, 10, TimeUnit.MINUTES);
            redis.rename(tmpKey, HOT_KEY);

            log.info("hot rank refreshed, articles={}", Math.min(all.size(), CAPACITY));
        } catch (Exception e) {
            log.error("hot rank refresh failed", e);
        }
    }

    /**
     * 热度分计算:点赞权重高,浏览次之。简化版,生产可加评论/转发/时间衰减。
     */
    private double calcScore(Article a) {
        long like = a.getLikeCount() == null ? 0 : a.getLikeCount();
        long view = a.getViewCount() == null ? 0 : a.getViewCount();
        return like * 2.0 + view * 1.0;
    }

    /** ZSet 空时的兜底:DB ORDER BY 查 Top N */
    private List<ArticleListItemVO> fallbackFromDb(int limit) {
        List<Article> articles = articleMapper.selectList(
                new LambdaQueryWrapper<Article>()
                        .eq(Article::getDeleted, 0)
                        .orderByDesc(Article::getLikeCount)
                        .last("LIMIT " + limit));
        return articles.stream().map(this::toVO).collect(Collectors.toList());
    }

    private ArticleListItemVO toVO(Article a) {
        ArticleListItemVO vo = new ArticleListItemVO();
        vo.setId(a.getId());
        vo.setAuthorId(a.getAuthorId());
        vo.setAuthorName(userClient.getUserName(a.getAuthorId()));
        vo.setTitle(a.getTitle());
        String content = a.getContent() == null ? "" : a.getContent();
        vo.setSummary(content.length() > 80 ? content.substring(0, 80) + "..." : content);
        vo.setTags(a.getTags());
        vo.setLikeCount(a.getLikeCount());
        vo.setViewCount(a.getViewCount());
        vo.setCreateTime(a.getCreateTime());
        return vo;
    }
}
