from __future__ import annotations

import io
import json
from unittest.mock import patch

import pytest

from memos_benchmark.ollama import OllamaClient, OllamaError

CHAT_DIGEST = "a" * 64
EMBED_DIGEST = "b" * 64


class FakeResponse(io.BytesIO):
    def __enter__(self) -> FakeResponse:
        return self

    def __exit__(self, *_args: object) -> None:
        self.close()


def _response(value: object) -> FakeResponse:
    return FakeResponse(json.dumps(value).encode("utf-8"))


def _selected_models() -> dict[str, object]:
    chat = {"tag": "chat:1", "ollama_model_id": CHAT_DIGEST}
    return {
        "extractor": chat,
        "summary": chat,
        "answer": chat,
        "embedding": {"tag": "embed:1", "ollama_model_id": EMBED_DIGEST},
        "reranker": None,
        "judge": None,
    }


def test_inventory_requires_full_digest_and_capabilities() -> None:
    responses = [
        _response({"version": "0.33.2"}),
        _response(
            {
                "models": [
                    {"name": "chat:1", "digest": CHAT_DIGEST},
                    {"name": "embed:1", "digest": EMBED_DIGEST},
                ]
            }
        ),
        _response(
            {
                "capabilities": ["completion", "thinking"],
                "details": {
                    "format": "gguf",
                    "family": "qwen3",
                    "parameter_size": "4B",
                    "quantization_level": "Q4_K_M",
                },
                "model_info": {
                    "qwen3.context_length": 1000,
                    "qwen3.embedding_length": 100,
                },
            }
        ),
        _response(
            {
                "capabilities": ["embedding"],
                "details": {
                    "format": "gguf",
                    "family": "qwen3",
                    "parameter_size": "0.6B",
                    "quantization_level": "Q8_0",
                },
                "model_info": {
                    "qwen3.context_length": 500,
                    "qwen3.embedding_length": 3,
                },
            }
        ),
    ]
    with patch("memos_benchmark.ollama.urlopen", side_effect=responses) as urlopen:
        observed = OllamaClient("http://ollama.test").inspect(_selected_models())

    assert observed["version"] == "0.33.2"
    assert observed["models"]["embed:1"]["embedding_length"] == 3
    assert observed["roles"]["answer"]["digest"] == CHAT_DIGEST
    assert urlopen.call_count == 4


def test_inventory_rejects_digest_drift_before_model_use() -> None:
    responses = [
        _response({"version": "0.33.2"}),
        _response({"models": [{"name": "chat:1", "digest": "c" * 64}]}),
    ]
    with (
        patch("memos_benchmark.ollama.urlopen", side_effect=responses),
        pytest.raises(OllamaError, match="digest drifted") as error,
    ):
        OllamaClient("http://ollama.test").inspect(_selected_models())

    assert error.value.kind == "MODEL_DRIFT"


def test_structured_chat_disables_thinking_and_records_usage() -> None:
    response = _response(
        {
            "done": True,
            "message": {"content": '{"answer":"ok"}'},
            "prompt_eval_count": 11,
            "eval_count": 3,
            "total_duration": 12_000_000,
            "load_duration": 2_000_000,
        }
    )
    ticks = iter((1_000_000, 16_000_000))
    with patch("memos_benchmark.ollama.urlopen", return_value=response) as urlopen:
        result = OllamaClient("http://ollama.test/", monotonic_ns=lambda: next(ticks)).chat_json(
            model="chat:1",
            messages=[{"role": "system", "content": "answer"}],
            schema={"type": "object"},
            temperature=0,
            seed=42,
        )

    request = urlopen.call_args.args[0]
    body = json.loads(request.data)
    assert request.full_url == "http://ollama.test/api/chat"
    assert body["think"] is False
    assert body["stream"] is False
    assert body["format"] == {"type": "object"}
    assert result.value == {"answer": "ok"}
    assert result.usage.as_dict() == {
        "input_tokens": 11,
        "output_tokens": 3,
        "embedding_tokens": 0,
        "model_calls": 1,
    }
    assert result.latency_ms == 15.0
    assert result.provider_total_ms == 12.0


def test_embedding_requires_stable_dimensions_and_records_tokens() -> None:
    response = _response(
        {
            "embeddings": [[1, 0, 0.5], [0, 1, 0.5]],
            "prompt_eval_count": 7,
            "total_duration": 10_000_000,
            "load_duration": 0,
        }
    )
    with patch("memos_benchmark.ollama.urlopen", return_value=response):
        result = OllamaClient("http://ollama.test").embed(
            model="embed:1", inputs=["hello", "world"]
        )

    assert result.vectors == [[1.0, 0.0, 0.5], [0.0, 1.0, 0.5]]
    assert result.usage.embedding_tokens == 7
    assert result.usage.model_calls == 1


def test_structured_chat_rejects_non_json_content() -> None:
    response = _response(
        {
            "done": True,
            "message": {"content": "not-json"},
            "prompt_eval_count": 1,
            "eval_count": 1,
        }
    )
    with (
        patch("memos_benchmark.ollama.urlopen", return_value=response),
        pytest.raises(OllamaError, match="content is not JSON"),
    ):
        OllamaClient("http://ollama.test").chat_json(
            model="chat:1",
            messages=[{"role": "user", "content": "x"}],
            schema={"type": "object"},
            temperature=0,
            seed=42,
        )
