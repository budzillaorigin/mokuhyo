"""OpenAI-compatible chat client for every tools/ drafting and eval script (BRIEF §7.1).

- Endpoint and model come from --endpoint/--model, else LLM_ENDPOINT/LLM_MODEL in the environment, else tools/.env,
  else tools/.env.example (the owner's Ollama server on the RTX 5090).
- Only models listed in models/approved_models.json may be used (CLAUDE.md rule 13); anything else is refused.
- Every request has a connect/read timeout and up to three retries with exponential backoff. After three consecutive
  failures of the primary model, calls fall back to the secondary (LLM_CHECK_MODEL, gpt-oss:20b).
- When the endpoint is unreachable, `EndpointDown` carries the exact command to re-run; scripts log it and exit
  non-zero without writing partial packs.
"""
from __future__ import annotations

import json
import os
import shutil
import socket
import subprocess
import sys
import time
import urllib.error
import urllib.request
from dataclasses import dataclass, field
from pathlib import Path

TOOLS = Path(__file__).resolve().parent
APPROVED = TOOLS / "models" / "approved_models.json"
CONNECT_TIMEOUT_S = 5
REQUEST_TIMEOUT_S = 600  # drafting a long passage on a 24B model at 16k context


def _http(url: str, data: bytes | None, timeout: int) -> bytes:
    """POST (or GET when data is None) and return the body. Uses urllib; falls back to the curl binary when the OS
    refuses this interpreter LAN access (macOS Local Network privacy blocks unsigned Pythons with EHOSTUNREACH)."""
    try:
        req = urllib.request.Request(url, data=data, headers={"Content-Type": "application/json"})
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            return resp.read()
    except urllib.error.URLError as e:
        reason = getattr(e, "reason", None)
        if isinstance(e, urllib.error.HTTPError) or not isinstance(reason, OSError) or reason.errno not in (65, 113):
            raise
        if not shutil.which("curl"):
            raise
    cmd = ["curl", "-sS", "--fail-with-body", "--connect-timeout", str(CONNECT_TIMEOUT_S), "--max-time", str(timeout), url]
    if data is not None:
        cmd += ["-H", "Content-Type: application/json", "--data-binary", "@-"]
    r = subprocess.run(cmd, input=data, capture_output=True, check=False)
    if r.returncode == 22:
        raise urllib.error.HTTPError(url, 500, r.stdout.decode("utf-8", "replace")[:300], None, None)
    if r.returncode != 0:
        raise urllib.error.URLError(OSError(r.returncode, r.stderr.decode("utf-8", "replace").strip() or "curl failed"))
    return r.stdout


class EndpointDown(RuntimeError):
    def __init__(self, message: str, rerun: str):
        super().__init__(message)
        self.rerun = rerun


class ModelNotApproved(RuntimeError):
    pass


def _dotenv() -> dict[str, str]:
    out: dict[str, str] = {}
    for name in (".env.example", ".env"):  # .env overrides the example
        p = TOOLS / name
        if p.exists():
            for line in p.read_text(encoding="utf-8").splitlines():
                line = line.strip()
                if line and not line.startswith("#") and "=" in line:
                    k, v = line.split("=", 1)
                    out[k.strip()] = v.strip()
    return out


def setting(name: str) -> str | None:
    return os.environ.get(name) or _dotenv().get(name)


def approved_models() -> list[dict]:
    return json.loads(APPROVED.read_text(encoding="utf-8"))["models"]


def check_approved(model: str) -> None:
    for m in approved_models():
        if model == m["tag"] or (m.get("match") == "prefix" and model.startswith(m["tag"])):
            return
    tags = ", ".join(m["tag"] for m in approved_models())
    raise ModelNotApproved(f"model '{model}' is not on the approved list (tools/models/approved_models.json): {tags}")


@dataclass
class Client:
    endpoint: str
    model: str
    fallback: str | None = None
    temperature: float = 0.7
    consecutive_failures: int = 0
    calls: int = 0
    log: list[str] = field(default_factory=list)

    @classmethod
    def from_args(cls, endpoint: str | None = None, model: str | None = None, fallback: str | None = None) -> Client:
        ep = endpoint or setting("LLM_ENDPOINT")
        md = model or setting("LLM_MODEL")
        fb = fallback if fallback is not None else setting("LLM_CHECK_MODEL")
        if not ep or not md:
            raise SystemExit("set LLM_ENDPOINT and LLM_MODEL (see tools/.env.example) or pass --endpoint/--model")
        check_approved(md)
        if fb:
            check_approved(fb)
        return cls(ep.rstrip("/"), md, fb)

    def url(self) -> str:
        return self.endpoint + ("/chat/completions" if self.endpoint.endswith("/v1") else "/v1/chat/completions")

    def ping(self) -> None:
        """Raises EndpointDown if the server doesn't answer at all."""
        base = self.endpoint.removesuffix("/v1")
        try:
            _http(base + "/api/tags", None, CONNECT_TIMEOUT_S)
        except (urllib.error.URLError, TimeoutError, socket.timeout, ConnectionError) as e:
            raise EndpointDown(f"drafting endpoint {self.endpoint} is unreachable: {e}", rerun_command()) from e

    def chat(self, messages: list[dict], *, json_schema: dict | None = None, temperature: float | None = None,
             max_tokens: int = 4096, model: str | None = None) -> str:
        """One completion; returns the text. Retries 3× with backoff; falls back to the secondary model after three
        consecutive primary failures."""
        use = model or (self.fallback if self.fallback and self.consecutive_failures >= 3 and not model else self.model)
        check_approved(use)
        body: dict = {"model": use, "messages": messages, "temperature": self.temperature if temperature is None else temperature,
                      "max_tokens": max_tokens, "stream": False}
        if json_schema is not None:
            body["response_format"] = {"type": "json_schema", "json_schema": {"name": "out", "schema": json_schema, "strict": True}}
        data = json.dumps(body).encode()
        last: Exception | None = None
        for attempt in range(4):  # first try + 3 retries
            if attempt:
                time.sleep(2 ** attempt)
            try:
                payload = json.loads(_http(self.url(), data, REQUEST_TIMEOUT_S))
                text = payload["choices"][0]["message"]["content"] or ""
                self.calls += 1
                if use == self.model:
                    self.consecutive_failures = 0
                return text
            except urllib.error.HTTPError as e:
                last = e
                detail = (e.read() if e.fp else str(e.msg).encode()).decode("utf-8", "replace")[:300]
                self.log.append(f"HTTP {e.code} from {use}: {detail}")
            except (urllib.error.URLError, TimeoutError, socket.timeout, ConnectionError) as e:
                last = e
                self.log.append(f"network error from {use}: {e}")
        if use == self.model:
            self.consecutive_failures += 1
        if isinstance(last, urllib.error.URLError) and not isinstance(last, urllib.error.HTTPError):
            raise EndpointDown(f"drafting endpoint {self.endpoint} failed: {last}", rerun_command())
        raise RuntimeError(f"{use} failed after retries: {last}")

    def chat_json(self, messages: list[dict], schema: dict | None = None, **kw) -> dict:
        text = self.chat(messages, json_schema=schema, **kw)
        return parse_json_object(text)


def parse_json_object(text: str) -> dict:
    text = text.strip()
    if text.startswith("```"):
        text = text.strip("`")
        text = text[text.find("{"):]
    start, end = text.find("{"), text.rfind("}")
    if start < 0 or end < start:
        raise ValueError("the model did not return a JSON object")
    return json.loads(text[start:end + 1])


def rerun_command() -> str:
    return "cd tools && uv run --group content python " + " ".join(sys.argv)


def add_args(parser) -> None:
    parser.add_argument("--endpoint", help="OpenAI-compatible base URL (default: LLM_ENDPOINT / tools/.env.example)")
    parser.add_argument("--model", help="model tag on the endpoint (default: LLM_MODEL); must be approved")
    parser.add_argument("--check-model", help="second-opinion/fallback model (default: LLM_CHECK_MODEL)")
