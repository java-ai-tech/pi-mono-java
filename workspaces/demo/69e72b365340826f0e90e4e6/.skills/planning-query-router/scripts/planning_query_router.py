#!/usr/bin/env python3
"""Task planner + intent router for knowledge/data SSE APIs.

Supports three modes:
- plan:    build task plan only
- route:   classify intent (knowledge/data) + plan
- execute: classify and call selected SSE API
"""

from __future__ import annotations

import json
import os
import re
import sys
import traceback
import urllib.error
import urllib.request
from dataclasses import dataclass
from typing import Any, Dict, List, Tuple

KNOWLEDGE_API_ENV_KEYS = (
    "KNOWLEDGE_QA_API_URL",
    "KNOWLEDGE_SSE_API_URL",
    "KNOWLEDGE_API_URL",
)

DATA_API_ENV_KEYS = (
    "DATA_QA_API_URL",
    "DATA_SSE_API_URL",
    "DATA_API_URL",
)

DATA_KEYWORDS = {
    "data",
    "database",
    "sql",
    "table",
    "column",
    "metric",
    "kpi",
    "query",
    "filter",
    "group by",
    "count",
    "sum",
    "avg",
    "同比",
    "环比",
    "统计",
    "数据",
    "报表",
    "趋势",
    "排行",
    "多少",
    "增长",
    "下降",
    "销售",
    "订单",
    "用户数",
}

KNOWLEDGE_KEYWORDS = {
    "what",
    "why",
    "how",
    "explain",
    "definition",
    "concept",
    "principle",
    "architecture",
    "guide",
    "tutorial",
    "best practice",
    "是什么",
    "为什么",
    "怎么",
    "如何",
    "解释",
    "定义",
    "原理",
    "介绍",
    "最佳实践",
}

TIME_OR_METRIC_PATTERNS = [
    r"\b\d{4}-\d{1,2}-\d{1,2}\b",
    r"\b(last|this|previous)\s+(day|week|month|quarter|year)\b",
    r"\b(top\s*\d+|rank|ranking)\b",
    r"\b(sum|count|avg|average|max|min|median|percentile)\b",
    r"\b(q\d|mtd|ytd|wtd)\b",
    r"(近\d+天|近\d+周|近\d+月|本周|本月|本季度|今年|昨天|今天)",
]


@dataclass
class RouteDecision:
    route: str
    confidence: float
    knowledge_score: float
    data_score: float
    matched_knowledge_signals: List[str]
    matched_data_signals: List[str]
    reason: str


def _safe_float(value: Any, default: float) -> float:
    try:
        return float(value)
    except (TypeError, ValueError):
        return default


def _first_non_blank(*values: Any) -> str:
    for value in values:
        text = str(value or "").strip()
        if text:
            return text
    return ""


def _extract_json_payload(raw: str) -> Dict[str, Any] | None:
    text = (raw or "").strip()
    if not text:
        return None

    try:
        direct = json.loads(text)
        if isinstance(direct, dict):
            return direct
    except json.JSONDecodeError:
        pass

    fenced = re.search(r"```(?:json)?\s*(\{[\s\S]*\})\s*```", text, flags=re.IGNORECASE)
    if fenced:
        try:
            parsed = json.loads(fenced.group(1))
            if isinstance(parsed, dict):
                return parsed
        except json.JSONDecodeError:
            pass

    brace_start = text.find("{")
    brace_end = text.rfind("}")
    if brace_start >= 0 and brace_end > brace_start:
        candidate = text[brace_start: brace_end + 1]
        try:
            parsed = json.loads(candidate)
            if isinstance(parsed, dict):
                return parsed
        except json.JSONDecodeError:
            return None
    return None


def _resolve_endpoint(raw_value: Any, env_keys: Tuple[str, ...]) -> str:
    if str(raw_value or "").strip():
        return str(raw_value).strip()
    for key in env_keys:
        env_val = os.getenv(key, "").strip()
        if env_val:
            return env_val
    return ""


def parse_request(raw: str) -> Dict[str, Any]:
    raw = (raw or "").strip()
    if not raw:
        return {
            "prompt": "",
            "history": [],
            "mode": "execute",
            "knowledge_api_url": "",
            "data_api_url": "",
            "headers": {},
            "request_body": None,
        }

    parsed = _extract_json_payload(raw) or {"prompt": raw}

    prompt = str(
        parsed.get("prompt")
        or parsed.get("query")
        or parsed.get("question")
        or parsed.get("input")
        or ""
    )

    history_raw = parsed.get("history") or parsed.get("messages") or []
    history = normalize_history(history_raw)

    mode = str(parsed.get("mode") or "execute").strip().lower()
    if mode not in {"plan", "route", "execute"}:
        mode = "execute"

    headers = parsed.get("headers") or {}
    if not isinstance(headers, dict):
        headers = {}

    return {
        "prompt": prompt,
        "history": history,
        "mode": mode,
        "route_hint": parsed.get("route_hint"),
        "knowledge_api_url": _resolve_endpoint(parsed.get("knowledge_api_url"), KNOWLEDGE_API_ENV_KEYS),
        "data_api_url": _resolve_endpoint(parsed.get("data_api_url"), DATA_API_ENV_KEYS),
        "headers": {str(k): str(v) for k, v in headers.items()},
        "request_body": parsed.get("request_body"),
        "timeout_s": _safe_float(parsed.get("timeout_s"), 120.0),
    }


def normalize_history(history_raw: Any) -> List[Dict[str, str]]:
    if not isinstance(history_raw, list):
        return []

    normalized: List[Dict[str, str]] = []
    for item in history_raw:
        if isinstance(item, str):
            normalized.append({"role": "unknown", "content": item})
            continue
        if not isinstance(item, dict):
            continue
        role = str(item.get("role") or "unknown")
        content = str(
            item.get("content")
            or item.get("text")
            or item.get("message")
            or item.get("value")
            or ""
        )
        if content.strip():
            normalized.append({"role": role, "content": content})
    return normalized


def _score_keywords(text: str, keywords: set[str]) -> Tuple[float, List[str]]:
    lowered = text.lower()
    score = 0.0
    matched: List[str] = []
    for kw in sorted(keywords, key=len, reverse=True):
        if kw in lowered:
            score += 1.0 if len(kw) <= 4 else 1.4
            matched.append(kw)
    return score, matched


def classify(prompt: str, history: List[Dict[str, str]], route_hint: Any) -> RouteDecision:
    hint = str(route_hint or "").strip().lower()
    if hint in {"knowledge", "data"}:
        return RouteDecision(
            route=hint,
            confidence=0.99,
            knowledge_score=1.0 if hint == "knowledge" else 0.0,
            data_score=1.0 if hint == "data" else 0.0,
            matched_knowledge_signals=["route_hint"] if hint == "knowledge" else [],
            matched_data_signals=["route_hint"] if hint == "data" else [],
            reason="forced_by_route_hint",
        )

    recent_history_text = "\n".join(h.get("content", "") for h in history[-8:])
    full_text = (prompt + "\n" + recent_history_text).strip().lower()

    knowledge_score, knowledge_hits = _score_keywords(full_text, KNOWLEDGE_KEYWORDS)
    data_score, data_hits = _score_keywords(full_text, DATA_KEYWORDS)

    for pattern in TIME_OR_METRIC_PATTERNS:
        if re.search(pattern, full_text, flags=re.IGNORECASE):
            data_score += 1.2
            data_hits.append(f"pattern:{pattern}")

    if re.search(r"\bselect\b|\bfrom\b|\bwhere\b|\bjoin\b", full_text):
        data_score += 1.5
        data_hits.append("pattern:sql-clause")

    if data_score == 0.0 and knowledge_score == 0.0:
        route = "knowledge"
        confidence = 0.51
        reason = "no_strong_signal_default_to_knowledge"
    elif data_score >= knowledge_score + 1.0:
        route = "data"
        total = data_score + knowledge_score
        confidence = min(0.55 + (data_score - knowledge_score) / (total + 1.0), 0.95)
        reason = "data_signals_dominate"
    elif knowledge_score >= data_score + 1.0:
        route = "knowledge"
        total = data_score + knowledge_score
        confidence = min(0.55 + (knowledge_score - data_score) / (total + 1.0), 0.95)
        reason = "knowledge_signals_dominate"
    else:
        # Tie-breaker: if any metric/time signal exists, prefer data; otherwise knowledge.
        route = "data" if any(h.startswith("pattern:") for h in data_hits) else "knowledge"
        confidence = 0.6
        reason = "mixed_signals_tie_breaker"

    return RouteDecision(
        route=route,
        confidence=round(confidence, 4),
        knowledge_score=round(knowledge_score, 4),
        data_score=round(data_score, 4),
        matched_knowledge_signals=knowledge_hits[:12],
        matched_data_signals=data_hits[:12],
        reason=reason,
    )


def build_plan(route: str, prompt: str, history: List[Dict[str, str]]) -> List[Dict[str, Any]]:
    history_size = len(history)
    base = [
        {
            "step": 1,
            "title": "Normalize Input",
            "details": "Extract prompt and recent context turns into a single intent analysis payload.",
        },
        {
            "step": 2,
            "title": "Intent Analysis",
            "details": "Score user intent signals from prompt and context to decide a route.",
        },
    ]

    if route == "data":
        route_specific = [
            {
                "step": 3,
                "title": "Data Question Structuring",
                "details": "Identify metrics, dimensions, filters, and time range before API call.",
            },
            {
                "step": 4,
                "title": "Route to Data QA SSE API",
                "details": "Send structured request and stream response chunks from data endpoint.",
            },
        ]
    else:
        route_specific = [
            {
                "step": 3,
                "title": "Knowledge Scope Structuring",
                "details": "Identify concept scope, constraints, and expected explanation depth.",
            },
            {
                "step": 4,
                "title": "Route to Knowledge QA SSE API",
                "details": "Send request and stream response chunks from knowledge endpoint.",
            },
        ]

    tail = [
        {
            "step": 5,
            "title": "Assemble Final Response",
            "details": "Aggregate SSE chunks into final response body with minimal post-processing.",
        },
        {
            "step": 6,
            "title": "Fallback Policy",
            "details": "If stream fails or route confidence is low, return dry-run result with manual reroute hint.",
        },
    ]

    plan = base + route_specific + tail
    plan.append(
        {
            "step": 7,
            "title": "Context Snapshot",
            "details": f"Prompt length={len(prompt)}, history_turns={history_size}.",
        }
    )
    return plan


def print_json(payload: Dict[str, Any]) -> None:
    print(json.dumps(payload, ensure_ascii=False, separators=(",", ":")))


def execute_sse(
    route: RouteDecision,
    prompt: str,
    history: List[Dict[str, str]],
    knowledge_api_url: str,
    data_api_url: str,
    headers: Dict[str, str],
    request_body: Any,
    timeout_s: float,
) -> Dict[str, Any]:
    requested_route = route.route
    selected_url = data_api_url if requested_route == "data" else knowledge_api_url
    selected_route = requested_route
    fallback_applied = False

    if not selected_url:
        fallback_url = knowledge_api_url if requested_route == "data" else data_api_url
        if fallback_url:
            selected_url = fallback_url
            selected_route = "knowledge" if requested_route == "data" else "data"
            fallback_applied = True

    if not selected_url:
        return {
            "executed": False,
            "reason": "missing_api_url",
            "requested_route": requested_route,
            "selected_route": selected_route,
            "selected_api_url": "",
            "sse_line_count": 0,
        }

    payload = request_body if isinstance(request_body, dict) else {
        "prompt": prompt,
        "history": history,
        "route": selected_route,
    }

    req_headers = {
        "Accept": "text/event-stream",
        "Content-Type": "application/json",
    }
    req_headers.update(headers)

    req = urllib.request.Request(
        url=selected_url,
        data=json.dumps(payload, ensure_ascii=False).encode("utf-8"),
        headers=req_headers,
        method="POST",
    )

    lines = 0
    done = False
    last_event = "message"

    with urllib.request.urlopen(req, timeout=timeout_s) as resp:
        for raw_line in resp:
            text = raw_line.decode("utf-8", errors="replace").strip()
            if not text or text.startswith(":"):
                continue
            if text.startswith("event:"):
                last_event = text.split(":", 1)[1].strip() or "message"
                continue
            if text.startswith("data:"):
                data = text.split(":", 1)[1].lstrip()
                if data == "[DONE]":
                    done = True
                    print("[sse][done] [DONE]")
                    break
                print(f"[sse][{last_event}] {data}")
                lines += 1

    return {
        "executed": True,
        "reason": "ok",
        "requested_route": requested_route,
        "selected_route": selected_route,
        "selected_api_url": selected_url,
        "sse_line_count": lines,
        "terminated_by_done": done,
        "fallback_applied": fallback_applied,
    }


def main() -> int:
    raw = " ".join(sys.argv[1:]).strip()
    req = parse_request(raw)
    if not req["prompt"].strip() and req["history"]:
        req["prompt"] = _first_non_blank(
            *(h.get("content", "") for h in reversed(req["history"]) if h.get("role") == "user")
        )

    route = classify(req["prompt"], req["history"], req.get("route_hint"))
    plan = build_plan(route.route, req["prompt"], req["history"])

    base_payload = {
        "mode": req["mode"],
        "prompt": req["prompt"],
        "history_turns": len(req["history"]),
        "decision": {
            "route": route.route,
            "confidence": route.confidence,
            "reason": route.reason,
            "knowledge_score": route.knowledge_score,
            "data_score": route.data_score,
            "matched_knowledge_signals": route.matched_knowledge_signals,
            "matched_data_signals": route.matched_data_signals,
        },
        "plan": plan,
    }

    if req["mode"] == "plan":
        output = {
            "mode": "plan",
            "plan": plan,
        }
        print_json(output)
        return 0

    if req["mode"] == "route":
        print_json(base_payload)
        return 0

    # execute mode
    print_json(
        {
            "mode": "execute",
            "status": "starting",
            "selected_route": route.route,
            "confidence": route.confidence,
        }
    )

    try:
        result = execute_sse(
            route=route,
            prompt=req["prompt"],
            history=req["history"],
            knowledge_api_url=req["knowledge_api_url"],
            data_api_url=req["data_api_url"],
            headers=req["headers"],
            request_body=req["request_body"],
            timeout_s=req["timeout_s"],
        )
        summary = {
            "mode": "execute",
            "status": "completed" if result.get("executed") else "dry-run",
            "decision": base_payload["decision"],
            "plan": plan,
            "execution": result,
        }
        print_json(summary)
        return 0 if result.get("reason") in {"ok", "missing_api_url"} else 1
    except urllib.error.HTTPError as exc:
        print_json(
            {
                "mode": "execute",
                "status": "error",
                "error_type": "http_error",
                "code": exc.code,
                "message": str(exc),
                "decision": base_payload["decision"],
            }
        )
        return 1
    except urllib.error.URLError as exc:
        print_json(
            {
                "mode": "execute",
                "status": "error",
                "error_type": "url_error",
                "message": str(exc),
                "decision": base_payload["decision"],
            }
        )
        return 1
    except Exception as exc:  # pylint: disable=broad-except
        print_json(
            {
                "mode": "execute",
                "status": "error",
                "error_type": "unexpected",
                "message": str(exc),
                "trace": traceback.format_exc(limit=3),
                "decision": base_payload["decision"],
            }
        )
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
