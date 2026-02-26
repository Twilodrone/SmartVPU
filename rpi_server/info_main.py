from __future__ import annotations

from pathlib import Path
from typing import List, Optional

from fastapi import FastAPI, HTTPException, Request
from fastapi.staticfiles import StaticFiles
from pydantic import BaseModel

app = FastAPI(title="VPU Pi File Server")

# Где лежат файлы на Raspberry
STORAGE_DIR = Path(__file__).resolve().parent / "storage"

# Раздаём всё содержимое storage по URL /files/...
# Пример: /files/630/2026.01.21/1.png
app.mount("/files", StaticFiles(directory=str(STORAGE_DIR)), name="files")


class ObjectInfoResponse(BaseModel):
    objectId: int
    version: str
    images: List[str]


def pick_latest_version_dir(obj_dir: Path) -> Optional[Path]:
    """
    Выбираем "последнюю" версию: по имени папки (лексикографически).
    Для формата YYYY.MM.DD это работает корректно.
    """
    versions = [p for p in obj_dir.iterdir() if p.is_dir()]
    if not versions:
        return None
    return sorted(versions, key=lambda p: p.name)[-1]


def list_images(version_dir: Path) -> List[Path]:
    allowed = {".jpg"}
    files = [p for p in version_dir.iterdir() if p.is_file() and p.suffix.lower() in allowed]
    # сортировка "1.png, 2.png, 10.png" как числа, если возможно
    def sort_key(p: Path):
        stem = p.stem
        return (0, int(stem)) if stem.isdigit() else (1, stem)
    return sorted(files, key=sort_key)


def base_url(req: Request) -> str:
    # учитывает реальный host/port из запроса
    return str(req.base_url).rstrip("/")


@app.get("/api/objects/{object_id}", response_model=ObjectInfoResponse)
def get_object(object_id: int, request: Request, version: Optional[str] = None):
    """
    Возвращает список URL картинок для объекта.
    Если version не задан — берём "последнюю" папку версии.
    """
    obj_dir = STORAGE_DIR / str(object_id)
    if not obj_dir.exists() or not obj_dir.is_dir():
        raise HTTPException(status_code=404, detail="Object not found")

    if version:
        version_dir = obj_dir / version
        if not version_dir.exists() or not version_dir.is_dir():
            raise HTTPException(status_code=404, detail="Version not found")
    else:
        version_dir = pick_latest_version_dir(obj_dir)
        if version_dir is None:
            raise HTTPException(status_code=404, detail="No versions for object")

    imgs = list_images(version_dir)
    if not imgs:
        raise HTTPException(status_code=404, detail="No images in version directory")

    b = base_url(request)
    ver = version_dir.name
    urls = [f"{b}/files/{object_id}/{ver}/{p.name}" for p in imgs]

    return ObjectInfoResponse(objectId=object_id, version=ver, images=urls)


@app.get("/api/health")
def health():
    return {"ok": True}
