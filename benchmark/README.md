# Benchmark

## 准备

```bash
# Ubuntu / WSL
sudo apt-get install -y wrk
# macOS
brew install wrk
```

## 1. 文章详情(测二级缓存效果)

```bash
# 冷启动:第一次基本是回源(慢),之后 L1 + L2 命中
wrk -t8 -c200 -d30s -s benchmark/article_detail.lua http://localhost:8080
```

期望结果(单机 article 实例, 8C16G):

| 场景 | QPS | P50 | P99 |
|---|---|---|---|
| 无任何缓存(直接走 DB) | ~1.2k | 80ms | 180ms |
| 仅 Redis | ~3.5k | 25ms | 70ms |
| Redis + Caffeine L1 | ~4.8k | 8ms | 45ms |

(对照实验:把 ArticleCacheManager.init() 里 maximumSize 改成 0 即可关闭 L1)

## 2. 点赞写入(测 MQ 削峰)

```bash
wrk -t8 -c500 -d30s -s benchmark/like.lua http://localhost:8080
```

期望:
- 接口侧 QPS ~ 8k+(全异步,只写 Redis + 发 MQ)
- DB 侧 article 表 UPDATE QPS 约 接口 QPS 的 1/5 ~ 1/10(批量合并效果)
- 通过 Grafana 对比 `mysql_global_status_com_update` 与接口 QPS

## 3. 限流降级验证

把 Sentinel 阈值临时改成 5 QPS:
```java
// SentinelConfig.java
rule.setCount(5);
```
重启 article 服务,然后:
```bash
wrk -t1 -c20 -d10s http://localhost:8081/api/article/1
```
应看到大量 `code: 5001, msg: "请求过于频繁,请稍后再试"` 响应。

## 记录数据

请把每次压测的输出贴到 `docs/benchmark-results.md`,这是面试时唯一能让"QPS 提升 4 倍"不是空话的依据。
