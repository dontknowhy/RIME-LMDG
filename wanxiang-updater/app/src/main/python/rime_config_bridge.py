"""高级设置 Android 桥接层。

Kotlin 侧通过 Chaquopy 调用本模块的函数；所有函数返回 JSON 字符串，
内部异常统一转成 {"ok": false, "error": ...}，避免抛 Python 异常。
"""

from __future__ import annotations

import gc
import json
import os
import traceback
from pathlib import Path

from advanced_settings.core import (
    MANAGED_RIME_CUSTOM_FILES,
    MANAGED_RIME_SOURCE_FILES,
    RimeKeyConflictEngine,
    RimeYamlEngine,
)
from advanced_settings.metadata import FILE_INDEX_META, RIME_KEY_MAP
from advanced_settings.operations import build_render_plan, save_document

_ENGINE = None
# 磁盘按键声明缓存：key -> (文件签名, claims)。避免每次进页面都重新编译多个 schema。
_CLAIMS_CACHE = {}


def _engine():
    global _ENGINE
    if _ENGINE is None:
        _ENGINE = RimeYamlEngine()
    return _ENGINE


def _cleanup(engine) -> None:
    """释放本轮编译产生的临时缓存，并回收 Python 内存，避免常驻膨胀。"""
    try:
        engine._begin_compile_session(engine._compile_root)
    except Exception:
        pass
    try:
        gc.collect()
    except Exception:
        pass


def _reset_claims() -> None:
    _CLAIMS_CACHE.clear()


def _managed_files():
    return sorted(MANAGED_RIME_SOURCE_FILES | MANAGED_RIME_CUSTOM_FILES)


def _ok(payload):
    payload = dict(payload)
    payload["ok"] = True
    return json.dumps(payload, ensure_ascii=False)


def _err(message, detail=""):
    return json.dumps(
        {"ok": False, "error": message, "detail": detail}, ensure_ascii=False
    )


def managed_files() -> str:
    return _ok({"files": _managed_files()})


def get_nav(workspace: str) -> str:
    """返回导航分类以及每类文件是否存在于工作区。"""
    try:
        root = Path(workspace)
        categories = []
        for category, entries in FILE_INDEX_META.items():
            files = []
            for entry in entries:
                file_name = entry["file"]
                files.append({
                    "file": file_name,
                    "name": entry["name"],
                    "exists": (root / file_name).exists(),
                })
            categories.append({"title": category, "files": files})
        return _ok({"categories": categories})
    except Exception as error:
        return _err(f"{type(error).__name__}: {error}", traceback.format_exc())


def scan(workspace: str) -> str:
    """检测工作区中存在的受管文件与解析错误。"""
    try:
        engine = _engine()
        root = Path(workspace)
        existing = []
        errors = []
        for name in _managed_files():
            path = root / name
            if not path.exists():
                continue
            existing.append(name)
            try:
                engine.load_file(path, default={})
            except Exception as error:
                errors.append({
                    "file": name,
                    "error": f"{type(error).__name__}: {error}",
                })
        return _ok({"existing": existing, "errors": errors})
    except Exception as error:
        return _err(f"{type(error).__name__}: {error}", traceback.format_exc())
    finally:
        _reset_claims()
        _cleanup(_engine())


def load_page(workspace: str, file_name: str, mode: str) -> str:
    try:
        plan = build_render_plan(_engine(), workspace, file_name, mode)
        return _ok(plan)
    except Exception as error:
        return _err(f"{type(error).__name__}: {error}", traceback.format_exc())
    finally:
        _cleanup(_engine())


def save_page(workspace: str, file_name: str, mode: str, edits_json: str) -> str:
    try:
        edits = json.loads(edits_json or "[]")
        result = save_document(_engine(), workspace, file_name, mode, edits)
        _reset_claims()
        return _ok(result)
    except Exception as error:
        return _err(f"{type(error).__name__}: {error}", traceback.format_exc())
    finally:
        _cleanup(_engine())


def read_raw(workspace: str, file_name: str) -> str:
    try:
        path = Path(workspace) / file_name
        if not path.is_file():
            return _err(f"文件不存在：{file_name}")
        return _ok({"file": file_name, "text": path.read_text(encoding="utf-8")})
    except Exception as error:
        return _err(f"{type(error).__name__}: {error}", traceback.format_exc())


def write_raw(workspace: str, file_name: str, text: str) -> str:
    try:
        from advanced_settings.core import is_managed_config_yaml

        path = Path(workspace) / file_name
        if not is_managed_config_yaml(path):
            return _err(f"非受管配置文件，拒绝写入：{file_name}")
        from ruamel.yaml import YAML

        validator = YAML()
        validator.allow_duplicate_keys = False
        try:
            validator.load(text)
        except Exception as error:
            return _err(f"YAML 解析失败：{type(error).__name__}: {error}")
        path.write_text(text, encoding="utf-8")
        return _ok({"file": file_name})
    except Exception as error:
        return _err(f"{type(error).__name__}: {error}", traceback.format_exc())


def import_switches(workspace: str) -> str:
    """从 wanxiang.schema.yaml 提取无 reset 的开关名与多选组的次选项。"""
    try:
        path = Path(workspace) / "wanxiang.schema.yaml"
        if not path.is_file():
            return _err("未找到 wanxiang.schema.yaml")
        data = _engine().load_file(path, default={})
        switches = data.get("switches", []) if isinstance(data, dict) else []
        names = []
        for switch in switches:
            if not isinstance(switch, dict):
                continue
            if "name" in switch:
                if "reset" not in switch:
                    names.append(switch["name"])
            elif "options" in switch and isinstance(switch["options"], list):
                options = switch["options"]
                if len(options) > 1:
                    names.extend(str(item) for item in options[1:])
        return _ok({"names": names})
    except Exception as error:
        return _err(f"{type(error).__name__}: {error}", traceback.format_exc())
    finally:
        _cleanup(_engine())


def _related_files(root: Path, target_id: str):
    engine = _engine()
    files = {target_id, "default.yaml"}

    default_path = root / "default.yaml"
    if default_path.is_file():
        try:
            data = engine.load_file(default_path, default={})
            for item in (data.get("schema_list", []) if isinstance(data, dict) else []) or []:
                if isinstance(item, dict) and "schema" in item:
                    files.add(f"{item['schema']}.schema.yaml")
        except Exception:
            pass

    target_path = root / target_id
    if target_path.is_file():
        try:
            data = engine.load_file(target_path, default={})
            schema = data.get("schema", {}) if isinstance(data, dict) else {}
            for dep in (schema.get("dependencies", []) if isinstance(schema, dict) else []) or []:
                files.add(f"{dep}.schema.yaml")
        except Exception:
            pass

    return files


def _effective_or_literal(engine, path: Path):
    """优先完整编译；缺失外部资源时回退到 schema + literal patch。"""
    try:
        return engine.compile_file(path, auto_custom=True)
    except Exception:
        try:
            data = engine.load_file(path, default={})
            custom_path = engine._custom_path_for_source(path)
            patch = {}
            if custom_path and Path(custom_path).is_file():
                custom_data = engine.load_file(custom_path, default={})
                if isinstance(custom_data, dict):
                    patch = custom_data.get("patch", {}) or {}
            return engine.apply_patch(data, patch)
        except Exception:
            return None


def _disk_claims(engine, root: Path, target_id: str):
    files = sorted(_related_files(root, target_id))
    signature = tuple(
        (name, (root / name).stat().st_mtime_ns if (root / name).is_file() else 0)
        for name in files
    )
    cache_key = (str(root), target_id)
    cached = _CLAIMS_CACHE.get(cache_key)
    if cached is not None and cached[0] == signature:
        return cached[1]

    conflict_engine = RimeKeyConflictEngine(engine)
    claims = []
    for file_name in files:
        path = root / file_name
        if not path.is_file():
            continue
        effective = _effective_or_literal(engine, path)
        if effective is None:
            continue
        claims.extend(conflict_engine.collect_claims(effective, file_name, origin="disk"))

    _CLAIMS_CACHE[cache_key] = (signature, claims)
    if len(_CLAIMS_CACHE) > 8:
        _CLAIMS_CACHE.clear()
        _CLAIMS_CACHE[cache_key] = (signature, claims)
    return claims


def detect_conflicts(workspace: str, target_id: str) -> str:
    """静态按键复用审计：只提示，不阻塞保存。"""
    try:
        engine = _engine()
        root = Path(workspace)
        conflict_engine = RimeKeyConflictEngine(engine)

        claims = _disk_claims(engine, root, target_id)
        # claims 已含各文件声明；这里仅需目标文件的实时值。
        effective = _effective_or_literal(engine, root / target_id)
        live_values = []
        if effective is not None:
            for live_path in (
                "super_tips/tips_key",
                "force_upper_aux/hotkey",
                "wanxiang_english/trigger",
            ):
                value = engine.get_path(effective, live_path, None)
                if value not in (None, ""):
                    live_values.append(str(value))

        if not live_values:
            return _ok({"conflicts": []})

        conflicts = conflict_engine.find_for_targets(
            live_values, claims, check_alphabet=False, source_file=target_id
        )
        result = []
        seen = set()
        for conflict in conflicts:
            line = conflict.format_line()
            if line in seen:
                continue
            seen.add(line)
            result.append({"severity": int(conflict.severity), "line": line})
        return _ok({"conflicts": result})
    except Exception as error:
        return _err(f"{type(error).__name__}: {error}", traceback.format_exc())
    finally:
        _cleanup(_engine())


def key_map() -> str:
    return _ok({"map": RIME_KEY_MAP})
