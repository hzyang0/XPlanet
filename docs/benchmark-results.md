# Benchmark Results

> 压测后请填入真实数据,简历上的"QPS 提升 X 倍"必须以此为依据。

## 环境

| 项目 | 配置 |
|---|---|
| CPU | (填) |
| 内存 | (填) |
| JDK | OpenJDK 17 |
| MySQL | 8.0 (Docker) |
| Redis | 7-alpine (Docker) |
| RocketMQ | 4.9.7 (Docker) |
| 压测客户端 | wrk |

## 1. 文章详情接口

### 1.1 无缓存(对照组)
```
wrk -t8 -c200 -d30s -s benchmark/article_detail.lua http://localhost:8080
# 把 ArticleServiceImpl.getDetail() 改成 return loadFromDb(articleId) 直接走 DB
```

输出贴这里:
```
(粘贴 wrk 结果)
```

### 1.2 仅 Redis(L1 关闭)
```
# Caffeine maximumSize 改成 0
```
```
(粘贴 wrk 结果)
```

### 1.3 二级缓存(L1 + L2)
```
(粘贴 wrk 结果)
```

### 对比表

| 场景 | QPS | P50 | P99 | Redis QPS | DB QPS |
|---|---|---|---|---|---|
| 无缓存 | | | | | |
| 仅 Redis | | | | | |
| 二级缓存 | | | | | |

## 2. 点赞接口削峰

```
wrk -t8 -c500 -d30s -s benchmark/like.lua http://localhost:8080
```
```
(粘贴 wrk 结果)
```

| 指标 | 数值 |
|---|---|
| 接口 QPS | |
| 接口 P99 | |
| DB UPDATE QPS(同期) | |
| 削峰比 | |

## 3. 限流降级

把 Sentinel 阈值改 5,压测后期望 95%+ 请求返回 5001。
```
(粘贴 wrk 结果)
```
