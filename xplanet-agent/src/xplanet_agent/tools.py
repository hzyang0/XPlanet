from __future__ import annotations

import ipaddress
import re
import socket
from html.parser import HTMLParser
from typing import Any, Callable, Protocol
from urllib.parse import urljoin, urlsplit, urlunsplit

import httpx

from .models import FetchedDocument, SearchHit, TaskCommand, ToolAction, ToolExecutionResult
from .providers import OFFLINE_CORPUS, SearchProvider


class DocumentFetcher(Protocol):
    name: str

    def fetch(self, command: TaskCommand, action: ToolAction) -> FetchedDocument: ...


class InternalSearchProvider(Protocol):
    name: str

    def search(self, command: TaskCommand, action: ToolAction, limit: int) -> ToolExecutionResult: ...


class OfflineInternalSearchProvider:
    name = "offline-internal"

    def search(self, command: TaskCommand, action: ToolAction, limit: int) -> ToolExecutionResult:
        query_terms = {term.lower() for term in (action.query or "").split() if len(term) > 2}

        def rank(document: FetchedDocument) -> tuple[int, str]:
            haystack = f"{document.title} {document.content}".lower()
            return (-sum(term in haystack for term in query_terms), document.url)

        hits = [
            SearchHit(
                url=document.url,
                title=document.title,
                snippet=document.content,
                sourceType="offline",
            )
            for document in sorted(OFFLINE_CORPUS, key=rank)[:limit]
        ]
        return ToolExecutionResult(action=action, searchHits=hits)


def _concise_internal_content(content: str, max_chars: int = 1600) -> str:
    """Bound and de-duplicate generated-looking Markdown before using it as evidence."""
    unique_lines: list[str] = []
    seen: set[str] = set()
    for raw_line in content.splitlines():
        line = re.sub(r"\s+", " ", raw_line).strip()
        if not line:
            continue
        identity = line.casefold()
        if identity in seen:
            continue
        seen.add(identity)
        unique_lines.append(line)
        if sum(len(item) + 1 for item in unique_lines) >= max_chars:
            break
    return "\n".join(unique_lines)[:max_chars].rstrip()


class HttpInternalSearchProvider:
    name = "mysql-fulltext"

    def __init__(
        self,
        base_url: str,
        internal_token: str,
        public_base_url: str = "http://localhost:8080",
        transport: httpx.BaseTransport | None = None,
    ) -> None:
        if not internal_token:
            raise ValueError("AGENT_INTERNAL_TOKEN is required for internal_search")
        self._base_url = base_url.rstrip("/")
        self._token = internal_token
        self._public_base_url = public_base_url.rstrip("/")
        self._transport = transport

    def search(self, command: TaskCommand, action: ToolAction, limit: int) -> ToolExecutionResult:
        timeout = min(float(command.deadlineSeconds), 10.0)
        with httpx.Client(transport=self._transport, timeout=timeout) as client:
            response = client.get(
                f"{self._base_url}/internal/article/knowledge/search",
                headers={"X-Agent-Token": self._token},
                params={"query": action.query or "", "topK": min(max(1, limit), 10)},
            )
            response.raise_for_status()
            body: dict[str, Any] = response.json()
        if body.get("code") != 0 or not isinstance(body.get("data"), list):
            raise ValueError("internal_search returned an invalid business response")
        hits = []
        for raw in body["data"]:
            article_id = raw.get("articleId")
            title = raw.get("title")
            content = raw.get("content")
            if not isinstance(article_id, int) or not isinstance(title, str) or not isinstance(content, str):
                raise ValueError("internal_search returned an invalid article item")
            concise_content = _concise_internal_content(content)
            if not concise_content:
                continue
            hits.append(
                SearchHit(
                    url=f"{self._public_base_url}/api/article/{article_id}",
                    title=title,
                    snippet=concise_content,
                    sourceType="internal",
                )
            )
        return ToolExecutionResult(action=action, searchHits=hits[:limit])


class OfflineDocumentFetcher:
    name = "offline-demo"

    def fetch(self, command: TaskCommand, action: ToolAction) -> FetchedDocument:
        for document in OFFLINE_CORPUS:
            if document.url == action.url:
                return document.model_copy(deep=True)
        raise ValueError("offline corpus does not contain the requested URL")


class _ReadableHtmlParser(HTMLParser):
    def __init__(self) -> None:
        super().__init__(convert_charrefs=True)
        self._ignored_depth = 0
        self._in_title = False
        self.title_parts: list[str] = []
        self.text_parts: list[str] = []

    def handle_starttag(self, tag: str, attrs: list[tuple[str, str | None]]) -> None:
        if tag in {"script", "style", "noscript", "svg"}:
            self._ignored_depth += 1
        if tag == "title":
            self._in_title = True

    def handle_endtag(self, tag: str) -> None:
        if tag in {"script", "style", "noscript", "svg"} and self._ignored_depth:
            self._ignored_depth -= 1
        if tag == "title":
            self._in_title = False

    def handle_data(self, data: str) -> None:
        value = " ".join(data.split())
        if not value or self._ignored_depth:
            return
        if self._in_title:
            self.title_parts.append(value)
        self.text_parts.append(value)


class HttpDocumentFetcher:
    """Bounded HTTP fetcher with redirect-by-redirect SSRF checks.

    DNS is validated before each request. A production deployment should additionally
    enforce the same policy at an egress proxy/firewall to close DNS-rebinding races.
    """

    name = "safe-http"
    _ALLOWED_CONTENT_TYPES = {
        "text/html",
        "application/xhtml+xml",
        "text/plain",
        "application/json",
    }

    def __init__(
        self,
        transport: httpx.BaseTransport | None = None,
        resolver: Callable[[str], list[str]] | None = None,
        max_bytes: int = 1_000_000,
        max_redirects: int = 3,
    ) -> None:
        self._transport = transport
        self._resolver = resolver or self._resolve
        self._max_bytes = max_bytes
        self._max_redirects = max_redirects

    def fetch(self, command: TaskCommand, action: ToolAction) -> FetchedDocument:
        current = self._normalize_and_validate(action.url or "")
        timeout = httpx.Timeout(min(float(command.deadlineSeconds), 30.0), connect=5.0)
        with httpx.Client(transport=self._transport, timeout=timeout, follow_redirects=False) as client:
            for redirect_count in range(self._max_redirects + 1):
                self._validate_destination(current)
                with client.stream(
                    "GET",
                    current,
                    headers={
                        "Accept": "text/html,application/xhtml+xml,text/plain,application/json",
                        "User-Agent": "XPlanetResearchAgent/1.0",
                    },
                ) as response:
                    if response.status_code in {301, 302, 303, 307, 308}:
                        if redirect_count >= self._max_redirects:
                            raise ValueError("web_fetch exceeded redirect limit")
                        location = response.headers.get("location")
                        if not location:
                            raise ValueError("web_fetch redirect had no location")
                        current = self._normalize_and_validate(urljoin(current, location))
                        continue
                    response.raise_for_status()
                    content_type = response.headers.get("content-type", "").split(";", 1)[0].lower()
                    if content_type not in self._ALLOWED_CONTENT_TYPES:
                        raise ValueError(f"web_fetch rejected content type: {content_type or 'missing'}")
                    body = bytearray()
                    for chunk in response.iter_bytes():
                        body.extend(chunk)
                        if len(body) > self._max_bytes:
                            raise ValueError("web_fetch response exceeded size limit")
                    encoding = response.encoding or "utf-8"
                    text = bytes(body).decode(encoding, errors="replace")
                    title, content = self._extract(text, content_type, current)
                    return FetchedDocument(
                        url=current,
                        title=title,
                        content=content[:50_000],
                        contentType=content_type,
                    )
        raise ValueError("web_fetch could not resolve redirect chain")

    def _normalize_and_validate(self, raw_url: str) -> str:
        parsed = urlsplit(raw_url.strip())
        if parsed.scheme not in {"http", "https"}:
            raise ValueError("web_fetch only allows http and https")
        if not parsed.hostname or parsed.username or parsed.password:
            raise ValueError("web_fetch URL must contain a public host and no credentials")
        if parsed.port not in {None, 80, 443}:
            raise ValueError("web_fetch only allows ports 80 and 443")
        return urlunsplit((parsed.scheme, parsed.netloc, parsed.path or "/", parsed.query, ""))

    def _validate_destination(self, url: str) -> None:
        host = urlsplit(url).hostname
        if not host:
            raise ValueError("web_fetch URL has no host")
        addresses = self._resolver(host)
        if not addresses:
            raise ValueError("web_fetch host did not resolve")
        for raw_address in addresses:
            address = ipaddress.ip_address(raw_address)
            if not address.is_global:
                raise ValueError("web_fetch rejected a non-public destination")

    @staticmethod
    def _resolve(host: str) -> list[str]:
        return sorted({item[4][0] for item in socket.getaddrinfo(host, None, type=socket.SOCK_STREAM)})

    @staticmethod
    def _extract(body: str, content_type: str, url: str) -> tuple[str, str]:
        if content_type in {"text/html", "application/xhtml+xml"}:
            parser = _ReadableHtmlParser()
            parser.feed(body)
            title = " ".join(parser.title_parts).strip() or url
            content = "\n".join(parser.text_parts).strip()
        else:
            title = url
            content = body.strip()
        if not content:
            raise ValueError("web_fetch returned no readable content")
        return title[:500], content


class ToolRegistry:
    def __init__(
        self,
        search_provider: SearchProvider,
        document_fetcher: DocumentFetcher,
        internal_search_provider: InternalSearchProvider | None = None,
    ) -> None:
        self._search_provider = search_provider
        self._document_fetcher = document_fetcher
        self._internal_search_provider = internal_search_provider or OfflineInternalSearchProvider()

    def execute(self, command: TaskCommand, action: ToolAction) -> ToolExecutionResult:
        if action.name == "web_search":
            return self._search_provider.search(command, action, command.maxSources)
        if action.name == "web_fetch":
            document = self._document_fetcher.fetch(command, action)
            return ToolExecutionResult(action=action, document=document)
        if action.name == "internal_search":
            return self._internal_search_provider.search(
                command, action, min(command.maxSources, 2)
            )
        raise ValueError("finish_research is a control action and cannot be executed as a tool")
