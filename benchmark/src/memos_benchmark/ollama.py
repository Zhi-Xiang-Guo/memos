"""Bounded standard-library Ollama client with exact model-identity checks."""

from __future__ import annotations

import json
import time
from collections.abc import Callable
from dataclasses import dataclass
from typing import Any
from urllib.error import HTTPError, URLError
from urllib.request import Request, urlopen

MAX_RESPONSE_BYTES = 32 * 1024 * 1024


class OllamaError(RuntimeError):
    """Provider failure without leaking response content."""

    def __init__(self, kind: str, message: str, status: int | None = None) -> None:
        super().__init__(message)
        self.kind = kind
        self.status = status


@dataclass(frozen=True)
class ProviderUsage:
    input_tokens: int = 0
    output_tokens: int = 0
    embedding_tokens: int = 0
    model_calls: int = 0

    def __add__(self, other: ProviderUsage) -> ProviderUsage:
        if not isinstance(other, ProviderUsage):
            return NotImplemented
        return ProviderUsage(
            input_tokens=self.input_tokens + other.input_tokens,
            output_tokens=self.output_tokens + other.output_tokens,
            embedding_tokens=self.embedding_tokens + other.embedding_tokens,
            model_calls=self.model_calls + other.model_calls,
        )

    def as_dict(self) -> dict[str, int]:
        return {
            "input_tokens": self.input_tokens,
            "output_tokens": self.output_tokens,
            "embedding_tokens": self.embedding_tokens,
            "model_calls": self.model_calls,
        }


@dataclass(frozen=True)
class ChatResult:
    value: dict[str, Any]
    usage: ProviderUsage
    latency_ms: float
    provider_total_ms: float | None
    provider_load_ms: float | None


@dataclass(frozen=True)
class EmbeddingResult:
    vectors: list[list[float]]
    usage: ProviderUsage
    latency_ms: float
    provider_total_ms: float | None
    provider_load_ms: float | None


class OllamaClient:
    def __init__(
        self,
        base_url: str,
        timeout_seconds: float = 300.0,
        monotonic_ns: Callable[[], int] = time.perf_counter_ns,
    ) -> None:
        if not base_url.startswith(("http://", "https://")):
            raise ValueError("Ollama base_url must use http or https")
        if timeout_seconds <= 0 or timeout_seconds > 1800:
            raise ValueError("Ollama timeout must be in (0, 1800] seconds")
        self._base_url = base_url.rstrip("/")
        self._timeout_seconds = timeout_seconds
        self._monotonic_ns = monotonic_ns

    def inspect(self, selected_models: dict[str, Any]) -> dict[str, Any]:
        """Verify full digests and required capabilities for every selected role."""

        version_response, _ = self._request("/api/version")
        version = _required_text(version_response.get("version"), "Ollama version")
        tags_response, _ = self._request("/api/tags")
        tags = tags_response.get("models")
        if not isinstance(tags, list):
            raise OllamaError("MALFORMED_RESPONSE", "Ollama tags response lacks models")
        inventory: dict[str, dict[str, Any]] = {}
        for value in tags:
            if not isinstance(value, dict):
                raise OllamaError("MALFORMED_RESPONSE", "Ollama model entry is invalid")
            name = value.get("name")
            digest = value.get("digest")
            if isinstance(name, str) and isinstance(digest, str):
                inventory[name] = value

        roles: dict[str, dict[str, Any]] = {}
        inspected: dict[str, dict[str, Any]] = {}
        for role, expected in selected_models.items():
            if expected is None:
                roles[role] = {"enabled": False}
                continue
            if not isinstance(expected, dict):
                raise OllamaError("CONFIGURATION", f"selected model role {role} is invalid")
            tag = _required_text(expected.get("tag"), f"selected model role {role} tag")
            expected_digest = _required_digest(
                expected.get("ollama_model_id"), f"selected model role {role} digest"
            )
            observed = inventory.get(tag)
            if observed is None:
                raise OllamaError("MODEL_NOT_FOUND", f"required Ollama model is absent: {tag}")
            actual_digest = _required_digest(observed.get("digest"), f"Ollama model {tag} digest")
            if actual_digest != expected_digest:
                raise OllamaError("MODEL_DRIFT", f"Ollama model digest drifted for {tag}")
            if tag not in inspected:
                show, _ = self._request("/api/show", {"model": tag, "verbose": False})
                capabilities = show.get("capabilities")
                details = show.get("details")
                model_info = show.get("model_info")
                if (
                    not isinstance(capabilities, list)
                    or any(not isinstance(item, str) for item in capabilities)
                    or not isinstance(details, dict)
                    or not isinstance(model_info, dict)
                ):
                    raise OllamaError(
                        "MALFORMED_RESPONSE", f"Ollama show response is invalid: {tag}"
                    )
                inspected[tag] = {
                    "digest": actual_digest,
                    "capabilities": sorted(capabilities),
                    "format": details.get("format"),
                    "family": details.get("family"),
                    "parameter_size": details.get("parameter_size"),
                    "quantization_level": details.get("quantization_level"),
                    "context_length": model_info.get("qwen3.context_length"),
                    "embedding_length": model_info.get("qwen3.embedding_length"),
                }
            required_capability = "embedding" if role == "embedding" else "completion"
            if required_capability not in inspected[tag]["capabilities"]:
                raise OllamaError(
                    "CAPABILITY_MISMATCH",
                    f"Ollama model {tag} lacks {required_capability} capability",
                )
            roles[role] = {"enabled": True, "tag": tag, "digest": actual_digest}
        return {"version": version, "models": inspected, "roles": roles}

    def chat_json(
        self,
        *,
        model: str,
        messages: list[dict[str, str]],
        schema: dict[str, Any],
        temperature: float,
        seed: int,
    ) -> ChatResult:
        if not messages or any(
            not isinstance(message, dict)
            or set(message) != {"role", "content"}
            or message["role"] not in {"system", "user", "assistant"}
            or not isinstance(message["content"], str)
            for message in messages
        ):
            raise ValueError("messages must contain role/content objects")
        if not isinstance(schema, dict) or not schema:
            raise ValueError("schema must be a non-empty object")
        response, latency_ms = self._request(
            "/api/chat",
            {
                "model": model,
                "messages": messages,
                "stream": False,
                "format": schema,
                "think": False,
                "keep_alive": "10m",
                "options": {"temperature": temperature, "seed": seed},
            },
        )
        if response.get("done") is not True:
            raise OllamaError("MALFORMED_RESPONSE", "Ollama chat response is incomplete")
        message = response.get("message")
        if not isinstance(message, dict) or not isinstance(message.get("content"), str):
            raise OllamaError("MALFORMED_RESPONSE", "Ollama chat response lacks content")
        try:
            value = json.loads(message["content"])
        except json.JSONDecodeError as exc:
            raise OllamaError("MALFORMED_RESPONSE", "Ollama chat content is not JSON") from exc
        if not isinstance(value, dict):
            raise OllamaError("MALFORMED_RESPONSE", "Ollama structured output is not an object")
        usage = ProviderUsage(
            input_tokens=_non_negative_int(response.get("prompt_eval_count"), "prompt tokens"),
            output_tokens=_non_negative_int(response.get("eval_count"), "output tokens"),
            model_calls=1,
        )
        return ChatResult(
            value=value,
            usage=usage,
            latency_ms=latency_ms,
            provider_total_ms=_duration_ms(response.get("total_duration")),
            provider_load_ms=_duration_ms(response.get("load_duration")),
        )

    def embed(self, *, model: str, inputs: list[str]) -> EmbeddingResult:
        if not inputs or any(not isinstance(value, str) or not value for value in inputs):
            raise ValueError("embedding inputs must contain non-empty strings")
        response, latency_ms = self._request(
            "/api/embed",
            {
                "model": model,
                "input": inputs,
                "truncate": False,
                "keep_alive": "10m",
            },
        )
        raw_vectors = response.get("embeddings")
        if not isinstance(raw_vectors, list) or len(raw_vectors) != len(inputs):
            raise OllamaError("MALFORMED_RESPONSE", "Ollama embedding count differs from inputs")
        vectors: list[list[float]] = []
        dimensions: int | None = None
        for raw in raw_vectors:
            if not isinstance(raw, list) or not raw:
                raise OllamaError("MALFORMED_RESPONSE", "Ollama embedding vector is empty")
            if any(not isinstance(value, (int, float)) or isinstance(value, bool) for value in raw):
                raise OllamaError("MALFORMED_RESPONSE", "Ollama embedding vector is non-numeric")
            vector = [float(value) for value in raw]
            dimensions = len(vector) if dimensions is None else dimensions
            if len(vector) != dimensions:
                raise OllamaError("MALFORMED_RESPONSE", "Ollama embedding dimensions differ")
            vectors.append(vector)
        usage = ProviderUsage(
            embedding_tokens=_non_negative_int(
                response.get("prompt_eval_count"), "embedding tokens"
            ),
            model_calls=1,
        )
        return EmbeddingResult(
            vectors=vectors,
            usage=usage,
            latency_ms=latency_ms,
            provider_total_ms=_duration_ms(response.get("total_duration")),
            provider_load_ms=_duration_ms(response.get("load_duration")),
        )

    def _request(
        self, path: str, body: dict[str, Any] | None = None
    ) -> tuple[dict[str, Any], float]:
        encoded = None if body is None else json.dumps(body, separators=(",", ":")).encode("utf-8")
        request = Request(
            f"{self._base_url}{path}",
            data=encoded,
            headers={"Content-Type": "application/json", "Accept": "application/json"},
            method="GET" if body is None else "POST",
        )
        started = self._monotonic_ns()
        try:
            with urlopen(request, timeout=self._timeout_seconds) as response:  # noqa: S310
                raw = response.read(MAX_RESPONSE_BYTES + 1)
        except HTTPError as exc:
            raise OllamaError("HTTP_ERROR", f"Ollama returned HTTP {exc.code}", exc.code) from exc
        except (URLError, TimeoutError, OSError) as exc:
            raise OllamaError("TRANSPORT", "Ollama request failed") from exc
        latency_ms = (self._monotonic_ns() - started) / 1_000_000
        if len(raw) > MAX_RESPONSE_BYTES:
            raise OllamaError("RESPONSE_TOO_LARGE", "Ollama response exceeded the byte limit")
        try:
            value = json.loads(raw)
        except json.JSONDecodeError as exc:
            raise OllamaError("MALFORMED_RESPONSE", "Ollama response is not JSON") from exc
        if not isinstance(value, dict):
            raise OllamaError("MALFORMED_RESPONSE", "Ollama response is not an object")
        return value, round(latency_ms, 3)


def _required_text(value: Any, field: str) -> str:
    if not isinstance(value, str) or not value:
        raise OllamaError("MALFORMED_RESPONSE", f"{field} is missing")
    return value


def _required_digest(value: Any, field: str) -> str:
    digest = _required_text(value, field)
    if len(digest) != 64 or any(character not in "0123456789abcdef" for character in digest):
        raise OllamaError("MALFORMED_RESPONSE", f"{field} is not a full lowercase digest")
    return digest


def _non_negative_int(value: Any, field: str) -> int:
    if not isinstance(value, int) or isinstance(value, bool) or value < 0:
        raise OllamaError("MALFORMED_RESPONSE", f"Ollama {field} is invalid")
    return value


def _duration_ms(value: Any) -> float | None:
    if value is None:
        return None
    return round(_non_negative_int(value, "duration") / 1_000_000, 3)
