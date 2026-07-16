from xplanet_agent.evaluation import evaluate_cases


def test_evaluation_names_index_validity_without_claiming_factual_support() -> None:
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
    assert summary["claimSupportRate"] is None
    assert "not factual support" in summary["claimSupportRateNote"]
