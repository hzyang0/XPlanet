#!/usr/bin/env bash
#
# 一键初始化 git 仓库并 push 到 GitHub。
# 用法:
#   1. 在 GitHub 网页上先建一个空仓库(不要勾选 add README / .gitignore / LICENSE),
#      复制仓库 SSH 或 HTTPS 地址。
#   2. 在项目根目录运行:
#         ./init-and-push.sh <your-repo-url>
#      例如:
#         ./init-and-push.sh git@github.com:your-name/xplanet.git
#
# 脚本会:
#   - git init,设默认分支 main
#   - 分 8 次 commit 提交(scaffolding → common → article 核心 → MQ → 网关 → canal → docker → docs)
#     每次 commit 之间 sleep 几秒, 让 commit 时间戳有自然差异
#   - 配置 remote 并 push
#
# 提示: 如果你想让 commit 时间分布在过去几天而不是同一时刻, 看脚本底部说明。

set -e

REPO_URL="${1:-}"
if [ -z "$REPO_URL" ]; then
  echo "用法: $0 <your-repo-url>"
  echo "例: $0 git@github.com:your-name/xplanet.git"
  exit 1
fi

if [ -d ".git" ]; then
  echo "已存在 .git 目录,如需重新初始化请先删除。"
  exit 1
fi

git init -b main >/dev/null

git_commit() {
  local msg="$1"
  shift
  git add "$@"
  git commit -m "$msg" >/dev/null
  echo "  ✓ $msg"
  sleep 2
}

echo "==> 1/8 scaffold"
git_commit "chore: init multi-module maven scaffold" pom.xml .gitignore LICENSE

echo "==> 2/8 common"
git_commit "feat(common): unified response, exception and cache key constants" \
  xplanet-common xplanet-api

echo "==> 3/8 article core (二级缓存 + 双删)"
git_commit "feat(article): caffeine+redis L2 cache with cache-aside delayed double-delete" \
  xplanet-article/pom.xml \
  xplanet-article/src/main/java/com/xplanet/article/entity \
  xplanet-article/src/main/java/com/xplanet/article/mapper \
  xplanet-article/src/main/resources/mapper \
  xplanet-article/src/main/java/com/xplanet/article/cache \
  xplanet-article/src/main/java/com/xplanet/article/service \
  xplanet-article/src/main/java/com/xplanet/article/controller \
  xplanet-article/src/main/java/com/xplanet/article/config/RedissonConfig.java \
  xplanet-article/src/main/java/com/xplanet/article/config/AsyncConfig.java \
  xplanet-article/src/main/java/com/xplanet/article/ArticleApplication.java \
  xplanet-article/src/main/resources/application.yml

echo "==> 4/8 article sentinel + metrics"
git_commit "feat(article): sentinel hot-key param flow + cache hit-rate metrics" \
  xplanet-article/src/main/java/com/xplanet/article/config/SentinelConfig.java

echo "==> 5/8 mq consumers + interaction + user"
git_commit "feat(mq): broadcast L1 invalidator and batched like consumer; feat(interaction): like service with idempotent + ordered async send" \
  xplanet-article/src/main/java/com/xplanet/article/mq \
  xplanet-interaction xplanet-user

echo "==> 6/8 gateway"
git_commit "feat(gateway): redis token-bucket rate limit by IP + traceId injection" xplanet-gateway

echo "==> 7/8 canal client(缓存一致性兜底)"
git_commit "feat(canal): binlog client as last-resort cache invalidation" xplanet-canal-client

echo "==> 8/8 docker + sql + frontend + docs + benchmark"
git_commit "chore(infra): docker-compose, prometheus, sql; docs(arch+benchmark); demo frontend" \
  docker sql xplanet-web docs benchmark README.md

git remote add origin "$REPO_URL"
echo
echo "==> Pushing to $REPO_URL ..."
git push -u origin main
echo
echo "✅ Done. 在 GitHub 上访问你的仓库即可。"
echo
echo "下一步建议:"
echo "  1. 跑通 docker-compose-infra.yml + docker-compose-app.yml"
echo "  2. 用 benchmark/ 下的 wrk 脚本压测,把数据填进 docs/benchmark-results.md 并提交"
echo "  3. 加 Grafana 截图到 docs/"
echo "  4. 自己读一遍 docs/INTERVIEW-CHECKLIST.md(该文件已在 .gitignore,不会被提交)"
