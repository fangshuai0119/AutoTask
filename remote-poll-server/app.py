"""
AutoTask remote poll backend

Client contract (RemotePollEventDispatcher):
  GET  /tasks/pending  -> { shouldExecute, taskChecksum, payload? }
  POST /tasks/report   -> { taskChecksum, success, message? }

Admin (optional API Key):
  POST /tasks/enqueue
  GET  /tasks
  DELETE /tasks/pending
  GET  /health
"""

from __future__ import annotations

import os
import threading
import time
from collections import deque
from typing import Any, Deque, Dict, Optional

from fastapi import Depends, FastAPI, Header, HTTPException
from fastapi.middleware.cors import CORSMiddleware
from pydantic import BaseModel, Field

API_KEY = os.environ.get("API_KEY", "").strip()
HOST = os.environ.get("HOST", "0.0.0.0")
PORT = int(os.environ.get("PORT", "8080"))
DEDUP_SECONDS = float(os.environ.get("DEDUP_SECONDS", "60"))

_lock = threading.Lock()
_pending: Deque[Dict[str, Any]] = deque()
_last_delivered: Dict[int, float] = {}
_reports: Deque[Dict[str, Any]] = deque(maxlen=200)


def _check_api_key(x_api_key: Optional[str] = Header(default=None, alias="X-Api-Key")) -> None:
    if not API_KEY:
        return
    if not x_api_key or x_api_key != API_KEY:
        raise HTTPException(status_code=401, detail="Invalid or missing X-Api-Key")


class PendingResponse(BaseModel):
    shouldExecute: bool = False
    taskChecksum: int = 0
    payload: Optional[str] = None


class StatusReport(BaseModel):
    taskChecksum: int
    success: bool
    message: Optional[str] = None


class EnqueueRequest(BaseModel):
    taskChecksum: int = Field(..., description="Client task checksum (Long)")
    payload: Optional[str] = Field(None, description="Optional payload for event extra")
    force: bool = Field(False, description="Ignore dedup window when true")


class EnqueueResponse(BaseModel):
    ok: bool
    queueSize: int
    message: str


app = FastAPI(
    title="AutoTask Remote Poll Server",
    description="Polling API for AutoTask remote trigger",
    version="1.0.0",
)

app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_methods=["*"],
    allow_headers=["*"],
)


@app.get("/health")
def health() -> Dict[str, str]:
    return {"status": "ok"}


@app.get("/tasks/pending", response_model=PendingResponse)
def get_pending() -> PendingResponse:
    now = time.time()
    with _lock:
        while _pending:
            item = _pending.popleft()
            checksum = int(item["taskChecksum"])
            last = _last_delivered.get(checksum, 0.0)
            if now - last < DEDUP_SECONDS and not item.get("force"):
                continue
            _last_delivered[checksum] = now
            return PendingResponse(
                shouldExecute=True,
                taskChecksum=checksum,
                payload=item.get("payload"),
            )
    return PendingResponse(shouldExecute=False, taskChecksum=0, payload=None)


@app.post("/tasks/report")
def post_report(body: StatusReport) -> Dict[str, Any]:
    record = {
        "taskChecksum": body.taskChecksum,
        "success": body.success,
        "message": body.message,
        "reportedAt": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()),
    }
    with _lock:
        _reports.appendleft(record)
    return {"ok": True, "received": record}


@app.post("/tasks/enqueue", response_model=EnqueueResponse, dependencies=[Depends(_check_api_key)])
def enqueue(body: EnqueueRequest) -> EnqueueResponse:
    item = {
        "taskChecksum": body.taskChecksum,
        "payload": body.payload,
        "force": body.force,
        "enqueued_at": time.time(),
    }
    with _lock:
        _pending.append(item)
        size = len(_pending)
    return EnqueueResponse(
        ok=True,
        queueSize=size,
        message=f"enqueued checksum={body.taskChecksum}",
    )


@app.get("/tasks", dependencies=[Depends(_check_api_key)])
def list_tasks() -> Dict[str, Any]:
    with _lock:
        pending = list(_pending)
        reports = list(_reports)[:50]
    return {
        "pendingCount": len(pending),
        "pending": pending,
        "recentReports": reports,
        "dedupSeconds": DEDUP_SECONDS,
    }


@app.delete("/tasks/pending", dependencies=[Depends(_check_api_key)])
def clear_pending() -> Dict[str, Any]:
    with _lock:
        n = len(_pending)
        _pending.clear()
    return {"ok": True, "cleared": n}


if __name__ == "__main__":
    import uvicorn

    uvicorn.run("app:app", host=HOST, port=PORT, reload=False)
