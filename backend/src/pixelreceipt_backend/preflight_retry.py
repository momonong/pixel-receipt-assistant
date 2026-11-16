"""Narrow no-generation proof for a durable Broker countTokens rate-limit refusal."""

RETRY_DELAYS = (60, 180)


def retryable_preflight(outcome, snapshot):
    preflight = outcome.get("token_preflight")
    route = snapshot.get("route_explain") or {}
    target = route.get("preflight_target_id") or route.get("selected_target_id")
    return (
        outcome.get("state") == "rejected"
        and outcome.get("error_code") == "token_preflight_rejected"
        and isinstance(preflight, dict)
        and preflight.get("state") == "rejected"
        and type(preflight.get("http_status")) is int
        and preflight["http_status"] == 429
        and bool(snapshot.get("model"))
        and preflight.get("model") == snapshot["model"]
        and bool(target)
        and preflight.get("target_id") == target
        and all(
            key in outcome and outcome[key] is None
            for key in (
                "provider",
                "model",
                "dispatched_at",
                "ledger_dispatched_at",
                "reservation_id",
                "ledger_state",
                "provider_request_id",
                "reported_input_tokens",
                "reported_output_tokens",
                "reported_neurons",
                "finish_reason",
                "response_truncated",
            )
        )
        and outcome.get("attempts") == []
        and outcome.get("ledger_charges") == []
        and "result" not in outcome
    )
