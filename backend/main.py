"""
Hi Sinchan backend — keeps your AI API key on a server instead of inside the Android app.

The app sends:  POST /v1/chat   Authorization: Bearer <APP_TOKEN>
                {"system": "...", "messages": [{"role": "user", "content": "..."}], "web_search": true}
and receives:   {"reply": "..."}

Providers (set PROVIDER):
  anthropic  – Claude via the Messages API; supports server-side web search (default)
  openai     – any OpenAI-compatible /chat/completions endpoint (OPENAI_BASE_URL)
"""
import os
import secrets
from typing import List, Literal

import httpx
from fastapi import FastAPI, Header, HTTPException
from pydantic import BaseModel, Field

PROVIDER = os.getenv("PROVIDER", "anthropic").lower()
APP_TOKEN = os.getenv("APP_TOKEN", "")
ANTHROPIC_API_KEY = os.getenv("ANTHROPIC_API_KEY", "")
ANTHROPIC_MODEL = os.getenv("ANTHROPIC_MODEL", "claude-sonnet-5-5")
OPENAI_API_KEY = os.getenv("OPENAI_API_KEY", "")
OPENAI_BASE_URL = os.getenv("OPENAI_BASE_URL", "https://api.openai.com/v1").rstrip("/")
OPENAI_MODEL = os.getenv("OPENAI_MODEL", "gpt-4o-mini")
MAX_TOKENS = int(os.getenv("MAX_TOKENS", "1024"))

app = FastAPI(title="Hi Sinchan backend", version="1.0.0")


class Turn(BaseModel):
    role: Literal["user", "assistant"]
    content: str = Field(max_length=20_000)


class ChatRequest(BaseModel):
    system: str = Field(default="", max_length=30_000)
    messages: List[Turn] = Field(min_length=1, max_length=60)
    web_search: bool = False


class ChatResponse(BaseModel):
    reply: str


def check_token(authorization: str | None) -> None:
    if not APP_TOKEN:
        raise HTTPException(500, "Server misconfigured: set APP_TOKEN")
    supplied = (authorization or "").removeprefix("Bearer ").strip()
    if not secrets.compare_digest(supplied, APP_TOKEN):
        raise HTTPException(401, "Invalid app token")


@app.get("/health")
def health():
    return {"ok": True, "provider": PROVIDER}


@app.post("/v1/chat", response_model=ChatResponse)
async def chat(req: ChatRequest, authorization: str | None = Header(default=None)):
    check_token(authorization)
    msgs = [m.model_dump() for m in req.messages]
    try:
        text = await call_model(req, msgs)
    except httpx.HTTPError as e:
        raise HTTPException(502, f"Could not reach the AI provider: {type(e).__name__}")
    if not text:
        raise HTTPException(502, "Empty reply from model")
    return ChatResponse(reply=text)


async def call_model(req: ChatRequest, msgs: list) -> str:
    async with httpx.AsyncClient(timeout=httpx.Timeout(90.0, connect=15.0)) as client:
        if PROVIDER == "anthropic":
            if not ANTHROPIC_API_KEY:
                raise HTTPException(500, "Server misconfigured: set ANTHROPIC_API_KEY")
            body = {"model": ANTHROPIC_MODEL, "max_tokens": MAX_TOKENS, "system": req.system, "messages": msgs}
            if req.web_search:
                body["tools"] = [{"type": "web_search_20250305", "name": "web_search", "max_uses": 3}]
            r = await client.post(
                "https://api.anthropic.com/v1/messages",
                headers={"x-api-key": ANTHROPIC_API_KEY, "anthropic-version": "2023-06-01"},
                json=body,
            )
            if r.status_code != 200:
                raise HTTPException(502 if r.status_code >= 500 else r.status_code, f"Anthropic error: {r.text[:300]}")
            data = r.json()
            text = "".join(b.get("text", "") for b in data.get("content", []) if b.get("type") == "text").strip()
        else:
            headers = {"Authorization": f"Bearer {OPENAI_API_KEY}"} if OPENAI_API_KEY else {}
            body = {
                "model": OPENAI_MODEL,
                "messages": [{"role": "system", "content": req.system}] + msgs,
                "temperature": 0.7,
                "max_tokens": MAX_TOKENS,
            }
            r = await client.post(f"{OPENAI_BASE_URL}/chat/completions", headers=headers, json=body)
            if r.status_code != 200:
                raise HTTPException(502 if r.status_code >= 500 else r.status_code, f"Provider error: {r.text[:300]}")
            text = (r.json()["choices"][0]["message"]["content"] or "").strip()
    return text
