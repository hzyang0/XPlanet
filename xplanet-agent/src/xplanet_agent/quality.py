from __future__ import annotations

import re


_CJK = re.compile(r"[\u3400-\u9fff]")
_WORDS = re.compile(r"[a-z0-9][a-z0-9_-]+", re.IGNORECASE)


def lexical_support_score(statement: str, evidence: str) -> float:
    """Cheap deterministic guardrail; the model critic still performs semantic review."""

    normalized_statement = _normalize(statement)
    normalized_evidence = _normalize(evidence)
    if normalized_statement and normalized_statement in normalized_evidence:
        return 1.0
    claim_terms = _terms(normalized_statement)
    evidence_terms = _terms(normalized_evidence)
    if not claim_terms:
        return 0.0
    coverage = len(claim_terms & evidence_terms) / len(claim_terms)
    return round(min(1.0, coverage), 4)


def _normalize(value: str) -> str:
    return re.sub(r"\s+", " ", value.lower()).strip()


def _terms(value: str) -> set[str]:
    terms = set(_WORDS.findall(value))
    cjk = "".join(_CJK.findall(value))
    if cjk:
        terms.update(cjk[index : index + 2] for index in range(max(1, len(cjk) - 1)))
    return terms
