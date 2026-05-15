package com.xplanet.article.config;

import com.alibaba.csp.sentinel.slots.block.RuleConstant;
import com.alibaba.csp.sentinel.slots.block.flow.param.ParamFlowItem;
import com.alibaba.csp.sentinel.slots.block.flow.param.ParamFlowRule;
import com.alibaba.csp.sentinel.slots.block.flow.param.ParamFlowRuleManager;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Sentinel 规则代码化配置(demo 用)。生产环境应该接 Nacos 动态推送。
 *
 * <h3>规则说明</h3>
 * - 资源: article:detail
 * - 维度: 第 0 个参数(articleId)
 * - 阈值: 每个 articleId 单机 50 QPS
 * - 突发热门文章(比如 articleId = 100)单独配置 200 QPS
 */
@Slf4j
@Component
public class SentinelConfig {

    @EventListener(ApplicationReadyEvent.class)
    public void initRules() {
        ParamFlowRule rule = new ParamFlowRule("article:detail")
                .setParamIdx(0) // articleId 是第 0 个参数
                .setCount(50)
                .setGrade(RuleConstant.FLOW_GRADE_QPS);

        // 热点文章白名单(突破基础阈值)
        ParamFlowItem hotItem = new ParamFlowItem();
        hotItem.setObject("100"); // Sentinel 这里需要字符串
        hotItem.setClassType(Long.class.getName());
        hotItem.setCount(200);
        rule.setParamFlowItemList(Collections.singletonList(hotItem));

        List<ParamFlowRule> rules = new ArrayList<>();
        rules.add(rule);
        ParamFlowRuleManager.loadRules(rules);

        log.info("sentinel param flow rules loaded: article:detail = 50 qps/articleId, hot={100→200}");
    }
}
