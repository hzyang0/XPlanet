import httpx
import pytest

from xplanet_agent.models import TaskCommand, ToolAction
from xplanet_agent.tools import HttpDocumentFetcher, HttpInternalSearchProvider


def command() -> TaskCommand:
    return TaskCommand(
        eventId="tool-event",
        eventType="AI_TASK_REQUESTED",
        taskId=3,
        runId="tool-run",
        userId=7,
        question="fetch security",
    )


def action(url: str) -> ToolAction:
    return ToolAction(name="web_fetch", url=url, reason="read source")


def test_http_fetcher_extracts_readable_text_with_explicit_bounds() -> None:
    def handler(request: httpx.Request) -> httpx.Response:
        return httpx.Response(
            200,
            headers={"content-type": "text/html; charset=utf-8"},
            text="<html><title>Guide</title><body><h1>Agent</h1><script>ignore()</script><p>Evidence</p></body></html>",
        )

    fetcher = HttpDocumentFetcher(
        transport=httpx.MockTransport(handler),
        resolver=lambda host: ["93.184.216.34"],
    )
    document = fetcher.fetch(command(), action("https://example.com/guide#section"))

    assert document.url == "https://example.com/guide"
    assert document.title == "Guide"
    assert "Agent" in document.content
    assert "Evidence" in document.content
    assert "ignore" not in document.content


@pytest.mark.parametrize(
    "url",
    [
        "file:///etc/passwd",
        "http://user:secret@example.com/",
        "http://example.com:8080/",
    ],
)
def test_http_fetcher_rejects_unsafe_url_shapes(url: str) -> None:
    fetcher = HttpDocumentFetcher(resolver=lambda host: ["93.184.216.34"])
    with pytest.raises(ValueError):
        fetcher.fetch(command(), action(url))


def test_http_fetcher_rejects_private_dns_and_private_redirects() -> None:
    private = HttpDocumentFetcher(resolver=lambda host: ["127.0.0.1"])
    with pytest.raises(ValueError, match="non-public"):
        private.fetch(command(), action("http://example.com/"))

    requests = []

    def handler(request: httpx.Request) -> httpx.Response:
        requests.append(str(request.url))
        return httpx.Response(302, headers={"location": "http://internal.test/admin"})

    def resolver(host: str) -> list[str]:
        return ["93.184.216.34"] if host == "example.com" else ["10.0.0.8"]

    redirecting = HttpDocumentFetcher(
        transport=httpx.MockTransport(handler),
        resolver=resolver,
    )
    with pytest.raises(ValueError, match="non-public"):
        redirecting.fetch(command(), action("https://example.com/"))
    assert requests == ["https://example.com/"]


def test_http_fetcher_rejects_large_or_binary_responses() -> None:
    resolver = lambda host: ["93.184.216.34"]
    binary = HttpDocumentFetcher(
        transport=httpx.MockTransport(
            lambda request: httpx.Response(200, headers={"content-type": "image/png"}, content=b"png")
        ),
        resolver=resolver,
    )
    with pytest.raises(ValueError, match="content type"):
        binary.fetch(command(), action("https://example.com/image"))

    large = HttpDocumentFetcher(
        transport=httpx.MockTransport(
            lambda request: httpx.Response(
                200, headers={"content-type": "text/plain"}, content=b"x" * 20
            )
        ),
        resolver=resolver,
        max_bytes=10,
    )
    with pytest.raises(ValueError, match="size limit"):
        large.fetch(command(), action("https://example.com/large"))


def test_internal_search_uses_token_bound_topk_and_public_article_url() -> None:
    captured = []

    def handler(request: httpx.Request) -> httpx.Response:
        captured.append(request)
        return httpx.Response(
            200,
            json={
                "code": 0,
                "data": [
                    {
                        "articleId": 42,
                        "title": "Checkpoint recovery",
                        "content": "Persist tool results before advancing the graph.",
                        "score": 1.2,
                    }
                ],
            },
        )

    provider = HttpInternalSearchProvider(
        base_url="http://article:8081",
        internal_token="test-token",
        public_base_url="http://localhost:8080",
        transport=httpx.MockTransport(handler),
    )
    result = provider.search(
        command(),
        ToolAction(name="internal_search", query="checkpoint", reason="reuse knowledge"),
        99,
    )

    assert captured[0].headers["x-agent-token"] == "test-token"
    assert captured[0].url.params["topK"] == "10"
    assert result.searchHits[0].sourceType == "internal"
    assert result.searchHits[0].url == "http://localhost:8080/api/article/42"


def test_internal_search_rejects_invalid_business_payload() -> None:
    provider = HttpInternalSearchProvider(
        base_url="http://article:8081",
        internal_token="test-token",
        transport=httpx.MockTransport(
            lambda request: httpx.Response(200, json={"code": 4004, "data": None})
        ),
    )
    with pytest.raises(ValueError, match="business response"):
        provider.search(
            command(),
            ToolAction(name="internal_search", query="checkpoint", reason="reuse knowledge"),
            5,
        )
