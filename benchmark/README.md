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

## 2. 点赞写入(测 Outbox + MQ + 持久化投影)

```bash
wrk -t8 -c500 -d30s -s benchmark/like.lua http://localhost:8080
```

当前 `like.lua` 只适合验证“同一用户重复点赞是幂等 no-op”，不能测完整写链路性能。
正式压测需要准备足够多的用户/Token，并让点赞与取消真实交替，至少记录：

- 状态真实变化请求数、重复 no-op 数、业务失败数；
- `like_outbox` 待发送数量、最老事件年龄和重试次数；
- `like_count_delta` 待投影/拒绝数量；
- interaction 接口 P50/P95/P99，Outbox 到投影完成的端到端延迟；
- `article.like_count` 与 `like_relation status=1` 聚合结果是否一致。

在多用户脚本和指标采集补齐前，不填写预期 QPS，也不把 HTTP 200 或重复 no-op 计为有效点赞吞吐。

## 记录数据

请把每次压测的输出贴到 `docs/benchmark-results.md`,这是面试时唯一能让"QPS 提升 4 倍"不是空话的依据。
