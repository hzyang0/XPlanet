package com.xplanet.canal;

import com.alibaba.otter.canal.client.CanalConnector;
import com.alibaba.otter.canal.client.CanalConnectors;
import com.alibaba.otter.canal.protocol.CanalEntry.*;
import com.alibaba.otter.canal.protocol.Message;
import com.xplanet.api.dto.ArticleChangeMessage;
import com.xplanet.common.constant.MqTopics;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import javax.annotation.PreDestroy;
import java.net.InetSocketAddress;
import java.util.List;

/**
 * Canal 客户端: 订阅 article 表 binlog, 任何 UPDATE/DELETE/INSERT 都发 MQ 触发缓存清理。
 *
 * <h3>作为缓存一致性的最终兜底</h3>
 * <p>双删依赖业务代码"先改库再删缓存"的完整执行,但在以下场景会失败:
 * <ul>
 *   <li>新人改业务代码漏写删缓存调用</li>
 *   <li>DBA 直接改库</li>
 *   <li>后台脚本批量数据修复</li>
 *   <li>双删第二删失败/丢失</li>
 * </ul>
 * Canal 监听 binlog 保证: 只要 DB 有变更, 缓存就会被清, 与业务代码解耦。
 *
 * <h3>位点持久化</h3>
 * <p>本 demo 使用 Canal 内置内存位点; 生产环境应使用 ZooKeeper 持久化位点,
 * 否则客户端重启会从最新位点开始消费, 中间漏掉的变更不会被捕获。
 */
@Slf4j
@Component
public class CanalClient {

    @Value("${canal.host:localhost}")
    private String host;

    @Value("${canal.port:11111}")
    private int port;

    @Value("${canal.destination:example}")
    private String destination;

    @Value("${canal.username:}")
    private String username;

    @Value("${canal.password:}")
    private String password;

    @Value("${canal.subscribe:xplanet\\.article}")
    private String subscribeFilter;

    private final RocketMQTemplate rocketMQTemplate;

    private CanalConnector connector;
    private volatile boolean running = false;
    private Thread worker;

    public CanalClient(RocketMQTemplate rocketMQTemplate) {
        this.rocketMQTemplate = rocketMQTemplate;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        connector = CanalConnectors.newSingleConnector(
                new InetSocketAddress(host, port), destination, username, password);
        running = true;
        worker = new Thread(this::loop, "canal-worker");
        worker.setDaemon(true);
        worker.start();
        log.info("CanalClient started, connecting to {}:{} destination={}", host, port, destination);
    }

    @PreDestroy
    public void stop() {
        running = false;
        if (connector != null) {
            try { connector.disconnect(); } catch (Exception ignore) {}
        }
        log.info("CanalClient stopped");
    }

    private void loop() {
        int batchSize = 1000;
        while (running) {
            try {
                connector.connect();
                connector.subscribe(subscribeFilter);
                connector.rollback();
                log.info("canal connected, filter={}", subscribeFilter);

                while (running) {
                    Message msg = connector.getWithoutAck(batchSize, 1000L, java.util.concurrent.TimeUnit.MILLISECONDS);
                    long batchId = msg.getId();
                    int size = msg.getEntries().size();
                    if (batchId == -1 || size == 0) {
                        continue;
                    }
                    try {
                        handleEntries(msg.getEntries());
                        connector.ack(batchId);
                    } catch (Exception e) {
                        log.error("canal handle batch failed, rollback. batchId={}", batchId, e);
                        connector.rollback(batchId);
                    }
                }
            } catch (Exception e) {
                log.error("canal loop exception, reconnect in 5s", e);
                try {
                    if (connector != null) connector.disconnect();
                } catch (Exception ignore) {}
                try { Thread.sleep(5000); } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }

    private void handleEntries(List<Entry> entries) {
        for (Entry entry : entries) {
            if (entry.getEntryType() == EntryType.TRANSACTIONBEGIN
                    || entry.getEntryType() == EntryType.TRANSACTIONEND) {
                continue;
            }
            if (entry.getEntryType() != EntryType.ROWDATA) continue;

            RowChange rowChange;
            try {
                rowChange = RowChange.parseFrom(entry.getStoreValue());
            } catch (Exception e) {
                log.error("parse rowChange failed", e);
                continue;
            }

            EventType evt = rowChange.getEventType();
            if (evt != EventType.UPDATE && evt != EventType.INSERT && evt != EventType.DELETE) {
                continue;
            }

            String table = entry.getHeader().getTableName();
            if (!"article".equalsIgnoreCase(table)) continue;

            for (RowData row : rowChange.getRowDatasList()) {
                Long articleId = extractId(evt == EventType.DELETE
                        ? row.getBeforeColumnsList() : row.getAfterColumnsList());
                if (articleId == null) continue;

                ArticleChangeMessage m = ArticleChangeMessage.builder()
                        .articleId(articleId)
                        .op(evt.name())
                        .timestamp(System.currentTimeMillis())
                        .build();
                rocketMQTemplate.convertAndSend(MqTopics.TOPIC_ARTICLE_CHANGE, m);
                log.info("canal->mq sent, articleId={}, op={}", articleId, evt.name());
            }
        }
    }

    private Long extractId(List<Column> cols) {
        for (Column c : cols) {
            if ("id".equalsIgnoreCase(c.getName())) {
                try { return Long.parseLong(c.getValue()); }
                catch (NumberFormatException e) { return null; }
            }
        }
        return null;
    }
}
