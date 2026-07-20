from xplanet_agent.evaluation import evaluate_cases


def test_evaluation_separates_index_integrity_from_lexical_claim_support() -> None:
    summary = evaluate_cases(
        [
            {
                "id": "case-1",
                "question": "How should a traceable agent be designed?",
                "maxSources": 2,
                "maxToolCalls": 2,
                "minSources": 2,
            }
        ]
    )

    assert summary["successRate"] == 1
    assert summary["citationIndexValidityRate"] == 1
    assert summary["claimSupportRate"] == 1
    assert summary["claimSupportThreshold"] == 0.55
    assert "human audit" in summary["claimSupportMethod"]
