"""Minimal OpenAI-compatible chat client for drafting pack content (Ollama, LM Studio, llama-server, vLLM).

Everything drafted through it is labeled source="llm" by the caller and shows the "AI-generated" badge until a
human reviews it (CLAUDE.md rules 10 and 19). The API key, if any, is read from an environment variable and only
sent to the endpoint it belongs to (rule 14).
"""

from __future__ import annotations

import json
import os
import urllib.request


def chat_json(endpoint: str, model: str, system: str, user: str, api_key_env: str = "LLM_API_KEY",
              temperature: float = 0.3, timeout: int = 300) -> dict:
    """One chat completion that must return a JSON object; raises on HTTP or parse errors."""
    url = endpoint.rstrip("/")
    if not url.endswith("/chat/completions"):
        url += "/chat/completions" if url.endswith("/v1") else "/v1/chat/completions"
    body = json.dumps({
        "model": model, "temperature": temperature, "response_format": {"type": "json_object"},
        "messages": [{"role": "system", "content": system}, {"role": "user", "content": user}],
    }).encode()
    headers = {"Content-Type": "application/json"}
    key = os.environ.get(api_key_env)
    if key:
        headers["Authorization"] = f"Bearer {key}"
    req = urllib.request.Request(url, data=body, headers=headers)
    with urllib.request.urlopen(req, timeout=timeout) as resp:
        content = json.load(resp)["choices"][0]["message"]["content"]
    start, end = content.find("{"), content.rfind("}")
    if start < 0 or end < start:
        raise ValueError("the model did not return a JSON object")
    return json.loads(content[start:end + 1])


def add_endpoint_args(parser) -> None:
    parser.add_argument("--endpoint", help="OpenAI-compatible base URL, e.g. http://localhost:11434/v1 (Ollama)")
    parser.add_argument("--model", help="model name at the endpoint, e.g. qwen3:8b")
    parser.add_argument("--api-key-env", default="LLM_API_KEY", help="environment variable holding the API key, if any")
    parser.add_argument("--limit", type=int, default=50, help="draft at most this many items per run")
