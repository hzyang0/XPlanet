(function (global) {
  "use strict";

  const root = global.XP = global.XP || {};
  const state = { currentArticleId: null, liked: false, replyParentId: 0 };

  function integer(value, fallback) {
    const number = Number(value);
    return Number.isSafeInteger(number) && number >= 0 ? number : (fallback || 0);
  }

  async function loadArticles() {
    const box = document.getElementById("articleList");
    box.innerHTML = '<div class="skeleton-card"></div><div class="skeleton-card"></div>';
    try {
      const page = await root.api.request("/api/article/list?pageNum=1&pageSize=12");
      const records = page && Array.isArray(page.records) ? page.records : [];
      if (!records.length) {
        box.innerHTML = '<div class="empty-copy">暂无文章，请确认后端和数据库已经启动。</div>';
        return;
      }
      box.innerHTML = records.map(renderArticleCard).join("");
    } catch (error) {
      box.innerHTML = '<div class="inline-error">文章加载失败：' + root.util.escapeHtml(error.message) + '</div>';
    }
  }

  function renderArticleCard(article) {
    const id = integer(article.id);
    const author = article.authorName || ("用户 " + integer(article.authorId));
    const tags = String(article.tags || "").split(",").filter(Boolean)
      .map(function (tag) { return '<span class="tag">' + root.util.escapeHtml(tag.trim()) + '</span>'; }).join("");
    return '<article class="article-card" data-article-id="' + id + '" tabindex="0">' +
      '<h3>' + root.util.escapeHtml(article.title || "未命名文章") + '</h3>' +
      '<p>' + root.util.escapeHtml(article.summary || article.content || "") + '</p>' +
      '<div class="article-card-meta"><span>' + root.util.escapeHtml(author) + '</span>' +
      '<span>♡ ' + integer(article.likeCount) + '</span><span>◉ ' + integer(article.viewCount) + '</span></div>' +
      '<div>' + tags + '</div></article>';
  }

  async function loadHot() {
    const box = document.getElementById("hotList");
    try {
      const articles = await root.api.request("/api/article/hot?limit=5") || [];
      if (!articles.length) {
        box.innerHTML = '<div class="empty-copy">热门榜等待定时刷新</div>';
        return;
      }
      box.innerHTML = articles.map(function (article, index) {
        return '<div class="hot-item" data-article-id="' + integer(article.id) + '" tabindex="0">' +
          '<b>' + String(index + 1).padStart(2, "0") + '</b><span>' + root.util.escapeHtml(article.title) +
          '</span><small>♡ ' + integer(article.likeCount) + '</small></div>';
      }).join("");
    } catch (_) {
      box.innerHTML = '<div class="empty-copy">热门加载失败</div>';
    }
  }

  async function openArticle(articleId) {
    const id = integer(articleId);
    if (!id) return;
    root.app.activate("community");
    try {
      const article = await root.api.request("/api/article/" + id);
      if (!article) throw new Error("文章不存在");
      state.currentArticleId = id;
      state.liked = false;
      document.getElementById("articleTitle").textContent = article.title || "未命名文章";
      document.getElementById("articleMeta").innerHTML =
        '<span>作者 ' + root.util.escapeHtml(article.authorName || ("用户 " + integer(article.authorId))) + '</span>' +
        '<span>♡ ' + integer(article.likeCount) + '</span><span>◉ ' + integer(article.viewCount) + '</span>';
      document.getElementById("articleContent").innerHTML = root.util.renderMarkdown(article.content || "");
      document.getElementById("likeArticleButton").textContent = "♡ 点赞";
      document.getElementById("articleListView").hidden = true;
      document.getElementById("articleDetailView").hidden = false;
      await loadComments();
      global.scrollTo({ top: 0, behavior: "smooth" });
    } catch (error) {
      root.app.toast("文章加载失败：" + error.message, true);
    }
  }

  function showList() {
    document.getElementById("articleDetailView").hidden = true;
    document.getElementById("articleListView").hidden = false;
    state.currentArticleId = null;
  }

  async function toggleLike() {
    if (!state.currentArticleId) return;
    if (!root.auth.isLoggedIn()) {
      root.app.toast("请先登录再点赞", true);
      return;
    }
    try {
      const method = state.liked ? "DELETE" : "POST";
      const changed = await root.api.request("/api/like/" + state.currentArticleId, { method: method });
      state.liked = !state.liked;
      document.getElementById("likeArticleButton").textContent = state.liked ? "♥ 已点赞" : "♡ 点赞";
      root.app.toast(changed === false ? "状态未变化，幂等请求已吸收" : (state.liked ? "点赞事件已可靠入队" : "已取消点赞"));
    } catch (error) {
      root.app.toast("点赞失败：" + error.message, true);
    }
  }

  async function loadComments() {
    const box = document.getElementById("commentList");
    try {
      const comments = await root.api.request("/api/comment/article/" + state.currentArticleId) || [];
      box.innerHTML = comments.length ? comments.map(function (comment) {
        return renderComment(comment, false);
      }).join("") : '<div class="empty-copy">还没有评论</div>';
    } catch (_) {
      box.innerHTML = '<div class="inline-error">评论加载失败</div>';
    }
  }

  function renderComment(comment, child) {
    const id = integer(comment.id);
    const user = comment.userName || ("用户 " + integer(comment.userId));
    const children = (comment.children || []).map(function (item) { return renderComment(item, true); }).join("");
    return '<div class="comment' + (child ? ' is-child' : '') + '"><b>' + root.util.escapeHtml(user) + '</b>' +
      '<small> · ' + root.util.escapeHtml(root.util.formatTime(comment.createTime)) + '</small>' +
      '<p>' + root.util.escapeHtml(comment.content || "") + '</p>' +
      (!child && id ? '<button class="text-button reply-button" data-comment-id="' + id + '" type="button">回复</button>' : '') +
      children + '</div>';
  }

  async function submitComment(event) {
    event.preventDefault();
    if (!state.currentArticleId) return;
    if (!root.auth.isLoggedIn()) {
      root.app.toast("请先登录再评论", true);
      return;
    }
    const input = document.getElementById("commentInput");
    const content = input.value.trim();
    if (!content) return;
    try {
      await root.api.request("/api/comment", {
        method: "POST",
        body: { articleId: state.currentArticleId, parentId: state.replyParentId, content: content }
      });
      input.value = "";
      input.placeholder = "写下你的评论…";
      state.replyParentId = 0;
      await loadComments();
      root.app.toast("评论已发布");
    } catch (error) {
      root.app.toast("评论失败：" + error.message, true);
    }
  }

  function bind() {
    document.getElementById("refreshArticlesButton").addEventListener("click", loadArticles);
    document.getElementById("backToArticlesButton").addEventListener("click", showList);
    document.getElementById("likeArticleButton").addEventListener("click", toggleLike);
    document.getElementById("commentForm").addEventListener("submit", submitComment);
    document.getElementById("articleList").addEventListener("click", function (event) {
      const card = event.target.closest("[data-article-id]");
      if (card) openArticle(card.dataset.articleId);
    });
    document.getElementById("hotList").addEventListener("click", function (event) {
      const card = event.target.closest("[data-article-id]");
      if (card) openArticle(card.dataset.articleId);
    });
    document.getElementById("commentList").addEventListener("click", function (event) {
      const button = event.target.closest(".reply-button");
      if (!button) return;
      state.replyParentId = integer(button.dataset.commentId);
      const input = document.getElementById("commentInput");
      input.placeholder = "回复评论 #" + state.replyParentId;
      input.focus();
    });
  }

  root.community = {
    bind: bind,
    load: function () { return Promise.all([loadArticles(), loadHot()]); },
    openArticle: openArticle,
    showList: showList
  };
})(window);
