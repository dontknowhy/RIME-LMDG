"""高级设置的 UI 无关编辑逻辑。

本模块把桌面版 mixin.py 中与 PySide6 控件耦合的保存算法（补丁/直写、
补丁智能增删、规范化比较）提取为纯函数，供 Android 桥接层调用。
输入统一为「路径 -> 界面当前值」，不依赖任何 Qt 类型。
"""

from __future__ import annotations

import copy
import os
import re
from collections.abc import Mapping, Sequence
from pathlib import Path
from typing import Any, Dict, List, Tuple

from ruamel.yaml import YAML
from ruamel.yaml.comments import CommentedMap, CommentedSeq

from .core import (
    MANAGED_RIME_CUSTOM_FILES,
    RimeYamlEngine,
)
from .metadata import (
    ALGEBRA_AUX_NAMES,
    ALGEBRA_SCHEME_NAMES,
    ALGEBRA_TIQUAN_SCHEMES,
    ALGEBRA_VARIANT_BY_FILE,
    FILE_INDEX_META,
    RIME_KEY_MAP,
    SCHEMA_META_CONFIG,
    sort_algebra_patch_items,
)

# 模糊音勾选项：与桌面版 widgets.AlgebraPatchWidget.fuzzy_map 保持一致。
FUZZY_MAP = (
    ("n / l", "wanxiang_algebra:/模糊音_nl"),
    ("r / y", "wanxiang_algebra:/模糊音_ry"),
    ("h / f", "wanxiang_algebra:/模糊音_hf"),
    ("r / l", "wanxiang_algebra:/模糊音_rl"),
    ("k / g", "wanxiang_algebra:/模糊音_kg"),
    ("en / eng", "wanxiang_algebra:/模糊音_en_eng"),
    ("in / ing", "wanxiang_algebra:/模糊音_in_ing"),
    ("c / ch", "wanxiang_algebra:/模糊音_c_ch"),
    ("z / zh", "wanxiang_algebra:/模糊音_z_zh"),
    ("s / sh", "wanxiang_algebra:/模糊音_s_sh"),
)

ALLOWED_SCHEMAS = ["wanxiang", "wanxiang_pro", "wanxiang_english", "wanxiang_t9"]


ABSENT = object()


# ---------------------------------------------------------------------------
# 基础工具
# ---------------------------------------------------------------------------

def to_plain(value: Any) -> Any:
    """ruamel 往返对象 -> 可 JSON 序列化的普通结构。"""
    if value is ABSENT:
        return None
    if isinstance(value, Mapping):
        return {str(k): to_plain(v) for k, v in value.items()}
    if isinstance(value, Sequence) and not isinstance(value, (str, bytes, bytearray)):
        return [to_plain(v) for v in value]
    if isinstance(value, bool):
        return bool(value)
    if isinstance(value, int):
        return int(value)
    if isinstance(value, float):
        return float(value)
    if value is None:
        return None
    return str(value)


def is_really_changed(cur: Any, base: Any) -> bool:
    """比较真实 YAML 值，不把「键不存在」、false、[]、{} 混为一谈。"""
    if cur is ABSENT and base is ABSENT:
        return False
    if cur is ABSENT or base is ABSENT:
        return True

    def canonical(value: Any) -> Any:
        if isinstance(value, Mapping):
            return {str(k): canonical(v) for k, v in value.items()}
        if isinstance(value, Sequence) and not isinstance(value, (str, bytes, bytearray)):
            return [canonical(v) for v in value]
        if value is None:
            return None
        if isinstance(value, bool):
            return bool(value)
        if isinstance(value, int):
            return int(value)
        if isinstance(value, float):
            return float(value)
        return str(value)

    return canonical(cur) != canonical(base)


def is_empty(value: Any) -> bool:
    if value is None or value == "":
        return True
    if isinstance(value, (list, dict)) and not value:
        return True
    return False


def smart_seq(value: Any) -> Any:
    if isinstance(value, list):
        seq = CommentedSeq(value)
        if all(len(str(x)) <= 10 and "\n" not in str(x) for x in value):
            seq.fa.set_flow_style()
        return seq
    return value


def _safe_assign(parent: Any, key: Any, new_val: Any) -> None:
    """精准在位更新，保留 YAML 容器的所有注释。"""
    if isinstance(parent, list):
        old_val = parent[key] if key < len(parent) else None
    else:
        old_val = parent.get(key) if hasattr(parent, "get") else None

    if isinstance(old_val, dict) and isinstance(new_val, dict):
        for k in list(old_val.keys()):
            if k not in new_val:
                del old_val[k]
        for k, v in new_val.items():
            _safe_assign(old_val, k, v)
    elif isinstance(old_val, list) and isinstance(new_val, list):
        min_len = min(len(old_val), len(new_val))
        for i in range(min_len):
            _safe_assign(old_val, i, new_val[i])
        if len(new_val) > len(old_val):
            for i in range(len(old_val), len(new_val)):
                old_val.append(new_val[i])
        elif len(old_val) > len(new_val):
            del old_val[len(new_val):]
    else:
        parent[key] = new_val


def _custom_path_for(engine: RimeYamlEngine, schema_path: Path) -> str:
    derived = engine._custom_path_for_source(schema_path)
    if Path(derived).name.lower() in MANAGED_RIME_CUSTOM_FILES:
        return derived
    return ""


def _safe_yaml():
    y = YAML(typ="safe")
    y.allow_duplicate_keys = False
    return y


# ---------------------------------------------------------------------------
# 元数据路径索引
# ---------------------------------------------------------------------------

def _iter_meta_sections(target_id: str, schema_data: Any, effective: Any):
    """产出 (meta_key, root_key, node_map)，与桌面版页面构建的匹配规则一致。"""
    for meta_k, info in SCHEMA_META_CONFIG.items():
        match_file = info.get("_match_file")
        match_files = info.get("_match_files") or ()
        has_match = bool(match_file or match_files)
        if match_file and match_file != target_id:
            continue
        if match_files and target_id not in match_files:
            continue
        root_key = info.get("_root_key", meta_k)
        in_base = isinstance(schema_data, Mapping) and root_key in schema_data
        in_effective = isinstance(effective, Mapping) and root_key in effective
        if not has_match and not (in_base or in_effective):
            continue
        yield meta_k, root_key, info, has_match


def _path_meta(target_id: str, schema_data: Any, effective: Any) -> Dict[str, Dict[str, Any]]:
    """路径 -> {type, baseline}。用于保存规划与值转换。"""
    result: Dict[str, Dict[str, Any]] = {}
    if target_id == "wanxiang_algebra.yaml":
        return result
    for _meta_k, root_key, info, _has_match in _iter_meta_sections(target_id, schema_data, effective):
        for node_key, node in info["nodes"].items():
            path = root_key if node_key == "__self__" else f"{root_key}/{node_key}"
            result[path] = {
                "type": node.get("type", "str"),
                "baseline": node.get("baseline", ""),
                "node": node,
            }
    return result


# ---------------------------------------------------------------------------
# 界面渲染计划
# ---------------------------------------------------------------------------

def _extract_comment(tree: Any, path: str) -> str:
    """从 ruamel 往返对象中取某个键的行内/紧随注释（首行）。"""
    if not path:
        return ""
    parts = path.split("/")
    current = tree
    try:
        for part in parts[:-1]:
            current = current[int(part)] if isinstance(current, list) else current[part]
        key = parts[-1]
        ca = getattr(current, "ca", None)
        items = getattr(ca, "items", None) if ca is not None else None
        if not items or key not in items:
            return ""
        entry = items[key]
        for candidate in (entry[2] if len(entry) > 2 else None, entry[1] if len(entry) > 1 else None):
            if candidate is None:
                continue
            raw = str(getattr(candidate, "value", candidate))
            # 只接受与值同行的行内注释；以换行开头的是紧随其后的独立注释，
            # 属于下一个键，不能算作当前字段的注释。
            if raw.startswith("\n") or raw.startswith("\r"):
                continue
            text = raw.strip()
            if not text:
                continue
            first = text.splitlines()[0].strip()
            return first.lstrip("#").strip()
        return ""
    except Exception:
        return ""


def _display_text(engine: RimeYamlEngine, field_type: str, value: Any) -> str:
    if value is None:
        return ""
    if field_type == "list_text":
        if isinstance(value, list):
            return "\n".join(str(item) for item in value)
        return str(value)
    if field_type == "raw_yaml":
        try:
            return engine.dump_text(value).rstrip("\n")
        except Exception:
            return str(value)
    if field_type in ("str", "int", "float", "number", "multiline_str"):
        if isinstance(value, bool):
            return "true" if value else "false"
        return str(value)
    return ""


# ---------------------------------------------------------------------------
# 动态控件 / 代数编辑器描述
# ---------------------------------------------------------------------------

def _display_scalar(value: Any) -> str:
    if value is None:
        return ""
    if isinstance(value, bool):
        return "true" if value else "false"
    if isinstance(value, (list, dict)):
        if isinstance(value, dict) and len(value) == 1 and list(value.values())[0] is None:
            return "{" + str(list(value.keys())[0]) + "}"
        return str(to_plain(value))
    return str(value)


def _kv_display(value: Any) -> str:
    if isinstance(value, bool):
        return "true" if value else "false"
    if isinstance(value, list):
        return "\n".join(_display_scalar(item) for item in value)
    if isinstance(value, dict) and len(value) == 1 and list(value.values())[0] is None:
        return "{" + str(list(value.keys())[0]) + "}"
    return str(value) if value is not None else ""


def _kv_kind(desc: str) -> Dict[str, Any]:
    lowered = (desc or "").lower()
    if "(true/false)" in lowered:
        return {"value_kind": "bool", "select_options": []}
    if "填列表" in (desc or "") or "数组" in (desc or ""):
        return {"value_kind": "text", "select_options": []}
    match = re.search(r"\(([a-zA-Z0-9_]+(?:/[a-zA-Z0-9_]+)+)\)", desc or "")
    if match:
        return {"value_kind": "select", "select_options": match.group(1).split("/")}
    if "数字" in (desc or ""):
        return {"value_kind": "number", "select_options": []}
    return {"value_kind": "line", "select_options": []}


def _algebra_variant(target_id: str) -> str:
    return ALGEBRA_VARIANT_BY_FILE.get(target_id, "base")


def _algebra_patch_descriptor(target_id: str, value: Any) -> Dict[str, Any]:
    variant = _algebra_variant(target_id)
    items = [str(x).strip() for x in value if str(x).strip()] if isinstance(value, list) else []
    fuzzy_paths = {path: label for label, path in FUZZY_MAP}
    scheme = None
    aux = None
    tiquan = False
    fuzzy: List[str] = []
    extras: List[str] = []
    for item in items:
        if item in fuzzy_paths:
            fuzzy.append(item)
            continue
        if "wanxiang_algebra:/" in item:
            name = item.split("/")[-1].strip()
            if name == "模糊音":
                fuzzy = list(fuzzy_paths.keys())
                continue
            if name in ALGEBRA_AUX_NAMES:
                aux = name
                continue
            if name in ALGEBRA_SCHEME_NAMES:
                scheme = name
                continue
            if name in [f"{s}提权" for s in ALGEBRA_TIQUAN_SCHEMES]:
                tiquan = True
                continue
        extras.append(item)
    return {
        "kind": "algebra_patch",
        "variant": variant,
        "scheme": scheme or "全拼",
        "aux": aux or "直接辅助",
        "tiquan": tiquan,
        "fuzzy": fuzzy,
        "extras": extras,
        "scheme_options": list(ALGEBRA_SCHEME_NAMES),
        "aux_options": list(ALGEBRA_AUX_NAMES),
        "tiquan_schemes": list(ALGEBRA_TIQUAN_SCHEMES),
        "fuzzy_options": [{"label": label, "path": path} for label, path in FUZZY_MAP],
    }


def _extract_compiled(dict_value: Any) -> Dict[str, Any]:
    if isinstance(dict_value, list):
        found = next(
            (
                item
                for item in dict_value
                if isinstance(item, Mapping) and ("__patch" in item or "__include" in item)
            ),
            None,
        )
        dict_value = found if found else {}
    return dict_value if isinstance(dict_value, Mapping) else {}


def _algebra_scheme_descriptor(node_type: str, value: Any) -> Dict[str, Any]:
    data = _extract_compiled(value)
    include = data.get("__include", "")
    if isinstance(include, list) and include:
        include = include[0]
    patch = data.get("__patch", "")
    if isinstance(patch, list) and patch:
        patch = patch[0]
    include_str = str(include)
    patch_str = str(patch)

    if node_type == "reverse_algebra":
        scheme = "自然码"
        if "wanxiang_algebra:/reverse/" in include_str:
            scheme = include_str.split("/")[-1].strip(" '\"[]")
        stroke = "hspzn"
        if "wanxiang_algebra:/reverse/" in patch_str:
            stroke = patch_str.split("/")[-1].strip(" '\"[]")
        return {
            "kind": "reverse_algebra",
            "scheme": scheme,
            "scheme_options": ["全拼", "自然码", "小鹤双拼", "微软双拼", "搜狗双拼", "智能ABC", "紫光双拼", "拼音加加"],
            "stroke": stroke,
            "stroke_options": [
                {"label": "hspzn (横竖撇捺折默认)", "value": "hspzn"},
                {"label": "hupvd (双拼专用)", "value": "hupvd"},
                {"label": "hslzy (乱序17)", "value": "hslzy"},
            ],
        }
    prefix = "english" if node_type == "english_algebra" else "mixed"
    default = "自然码" if node_type == "english_algebra" else "全拼"
    scheme = default
    if f"wanxiang_algebra:/{prefix}/" in patch_str:
        scheme = patch_str.split("/")[-1].strip(" '\"[]")
    return {
        "kind": node_type,
        "scheme": scheme,
        "scheme_options": ["全拼", "自然码", "小鹤双拼", "微软双拼", "搜狗双拼", "智能ABC", "紫光双拼", "拼音加加", "自然龙", "汉心龙"],
    }


def _schema_options(workspace_path: Path) -> List[Dict[str, str]]:
    options = []
    for schema_id in ALLOWED_SCHEMAS:
        file_path = workspace_path / f"{schema_id}.schema.yaml"
        if not file_path.is_file():
            continue
        name = schema_id
        try:
            for line in file_path.read_text(encoding="utf-8").splitlines():
                line = line.strip()
                if line.startswith("name:"):
                    name = line.split("name:", 1)[1].strip().strip("'\"")
                    break
        except Exception:
            pass
        options.append({"id": schema_id, "name": name})
    return options


def _attach_dynamic(
    workspace_path: Path,
    target_id: str,
    node_type: str,
    node: Mapping[str, Any],
    value: Any,
    field: Dict[str, Any],
) -> None:
    if node_type == "dynamic_list":
        rows = [{"text": _display_scalar(item)} for item in value] if isinstance(value, list) else []
        if not rows:
            rows = [{"text": ""}]
        field["dynamic"] = {"kind": "list", "rows": rows}
    elif node_type == "dynamic_map":
        rows = []
        if isinstance(value, Mapping):
            for key, child in value.items():
                rows.append({
                    "text": f"{_display_scalar(key)}: {_display_scalar(child)}",
                    "key": to_plain(key),
                    "value": to_plain(child),
                })
        if not rows:
            rows = [{"text": "", "key": "", "value": None}]
        field["dynamic"] = {"kind": "map", "rows": rows}
    elif node_type == "dynamic_kv_list":
        preset = node.get("preset_keys", {}) or {}
        rows = []
        if isinstance(value, Mapping):
            for key, child in value.items():
                row = {"key": key, "value": _kv_display(child)}
                row.update(_kv_kind(preset.get(key, "")))
                rows.append(row)
        field["dynamic"] = {
            "kind": "kv_list",
            "preset_keys": to_plain(preset),
            "rows": rows,
        }
    elif node_type == "schema_checkboxes":
        active = []
        if isinstance(value, list):
            for item in value:
                if isinstance(item, Mapping) and "schema" in item:
                    active.append(item["schema"])
        field["dynamic"] = {
            "kind": "schema_checkboxes",
            "options": _schema_options(workspace_path),
            "active": active,
        }
    elif node_type == "dynamic_block_list":
        template = node.get("template", {}) or {}
        rows = []
        if isinstance(value, list):
            for block in value:
                rows.append(_block_row_descriptor(template, block))
        if not rows:
            rows.append(_block_row_descriptor(template, {}))
        field["dynamic"] = {
            "kind": "block",
            "template": {
                key: {
                    "title": info.get("title", key),
                    "type": info.get("type", "str"),
                    "desc": info.get("desc", ""),
                    **({"options": to_plain(info["options"])} if "options" in info else {}),
                    **({"preset_keys": to_plain(info["preset_keys"])} if "preset_keys" in info else {}),
                    **({"visible_if": to_plain(info["visible_if"])} if "visible_if" in info else {}),
                }
                for key, info in template.items()
            },
            "rows": rows,
        }
    elif node_type == "algebra_patch":
        field["dynamic"] = _algebra_patch_descriptor(target_id, value)
    elif node_type in ("reverse_algebra", "english_algebra", "mixed_algebra"):
        field["dynamic"] = _algebra_scheme_descriptor(node_type, value)


def _block_text(info: Mapping[str, Any], value: Any) -> str:
    if isinstance(value, list):
        if all(len(str(x)) <= 10 and "\n" not in str(x) for x in value):
            return "[" + ", ".join(str(x) for x in value) + "]"
        return "\n".join(str(x) for x in value)
    return str(value) if value is not None else ""


def _block_row_descriptor(template: Mapping[str, Any], block: Any) -> Dict[str, Any]:
    original = to_plain(block) if isinstance(block, Mapping) else {}
    fields: Dict[str, Any] = {}
    for key, info in template.items():
        present = isinstance(block, Mapping) and key in block
        value = block.get(key) if present else None
        field_type = info.get("type", "str")
        entry: Dict[str, Any] = {"title": info.get("title", key), "type": field_type, "present": present}
        if field_type == "action_kv":
            preset = info.get("preset_keys", {}) or {}
            action_key, action_value = "", ""
            if isinstance(block, Mapping):
                for pk in preset:
                    if pk in block:
                        action_key = pk
                        action_value = _display_scalar(block[pk])
                        break
            entry.update({
                "action_key": action_key,
                "action_value": action_value,
                "preset_keys": to_plain(preset),
            })
        elif field_type == "bool":
            if present:
                bool_val = str(value).lower() == "true" if isinstance(value, str) else bool(value)
                entry["tri"] = 1 if bool_val else 0
            else:
                entry["tri"] = -1
        elif field_type == "select":
            entry["options"] = to_plain(info.get("options", []))
            entry["text"] = "true" if value is True else "false" if value is False else (str(value) if value is not None else "")
        elif field_type in ("list_text", "raw_yaml"):
            entry["text"] = _block_text(info, value)
        else:
            if isinstance(value, list):
                entry["text"] = "[" + ", ".join(str(x) for x in value) + "]"
            else:
                entry["text"] = str(value) if value is not None else ""
        fields[key] = entry
    return {"original": original, "fields": fields}


def build_render_plan(
    engine: RimeYamlEngine,
    workspace: str,
    target_id: str,
    mode: str,
) -> Dict[str, Any]:
    workspace_path = Path(workspace)
    schema_path = workspace_path / target_id
    if not schema_path.exists():
        raise FileNotFoundError(f"文件不存在：{target_id}")

    custom_path = _custom_path_for(engine, schema_path)
    schema_data = engine.load_file(schema_path, default={})
    patch_data = (
        engine.load_file(custom_path, default={})
        if custom_path and Path(custom_path).exists()
        else {}
    )
    patch = patch_data.get("patch", {}) if isinstance(patch_data, Mapping) else {}
    source_effective = engine.apply_patch(schema_data, patch)
    try:
        effective = engine.compile_file(schema_path, auto_custom=True)
    except Exception:
        effective = source_effective

    is_direct = mode == "direct"
    sections: List[Dict[str, Any]] = []

    if target_id == "wanxiang_algebra.yaml":
        for version_key, version_val in (schema_data or {}).items():
            if not isinstance(version_val, Mapping):
                continue
            fields = []
            for input_type, rules_val in version_val.items():
                path = f"{version_key}/{input_type}"
                fields.append({
                    "path": path,
                    "type": "raw_yaml",
                    "title": str(input_type),
                    "desc": "正则转写段落",
                    "value": to_plain(rules_val),
                    "text": _display_text(engine, "raw_yaml", rules_val),
                    "present": True,
                })
            if fields:
                sections.append({
                    "key": str(version_key),
                    "title": f"📁 {version_key}",
                    "desc": "",
                    "root": str(version_key),
                    "fields": fields,
                })
    else:
        for meta_k, root_key, info, _has_match in _iter_meta_sections(target_id, schema_data, effective):
            fields = []
            for node_key, node in info["nodes"].items():
                path = root_key if node_key == "__self__" else f"{root_key}/{node_key}"
                node_type = node.get("type", "str")
                schema_marker = engine.get_path(schema_data, path, ABSENT)
                edits_directive = (
                    node.get("baseline") == "source_effective"
                    or path.endswith("/__patch")
                    or path.endswith("/__include")
                    or node_type in ("algebra_patch", "reverse_algebra", "english_algebra", "mixed_algebra")
                )
                display_tree = source_effective if (not is_direct and edits_directive) else effective
                display_marker = engine.get_path(display_tree, path, ABSENT)

                field: Dict[str, Any] = {
                    "path": path,
                    "type": node_type,
                    "title": node.get("title", node_key),
                    "desc": node.get("desc", ""),
                    "present": display_marker is not ABSENT,
                    "schema_present": schema_marker is not ABSENT,
                    "schema_value": to_plain(None if schema_marker is ABSENT else schema_marker),
                }
                if display_marker is not ABSENT:
                    field["value"] = to_plain(display_marker)
                if node_type in ("str", "int", "float", "number", "multiline_str", "list_text", "raw_yaml"):
                    field["text"] = _display_text(
                        engine, node_type, None if display_marker is ABSENT else display_marker
                    )
                if "options" in node:
                    field["options"] = to_plain(node["options"])
                if "action_btn" in node:
                    field["action_btn"] = node["action_btn"]
                comment = (
                    _extract_comment(schema_data, path)
                    or _extract_comment(source_effective, path)
                    or _extract_comment(effective, path)
                )
                if comment:
                    field["comment"] = comment
                if "template" in node:
                    field["template"] = {
                        k: {
                            "title": v.get("title", k),
                            "type": v.get("type", "str"),
                            "desc": v.get("desc", ""),
                            **({"options": to_plain(v["options"])} if "options" in v else {}),
                            **({"template": to_plain(v["template"])} if "template" in v else {}),
                            **({"visible_if": to_plain(v["visible_if"])} if "visible_if" in v else {}),
                        }
                        for k, v in node["template"].items()
                    }
                if node_type in (
                    "dynamic_list", "dynamic_map", "dynamic_kv_list", "dynamic_block_list",
                    "schema_checkboxes", "algebra_patch", "reverse_algebra",
                    "english_algebra", "mixed_algebra",
                ):
                    _attach_dynamic(
                        workspace_path,
                        target_id,
                        node_type,
                        node,
                        None if display_marker is ABSENT else display_marker,
                        field,
                    )
                fields.append(field)

            if fields:
                sections.append({
                    "key": meta_k,
                    "title": info.get("_title", meta_k),
                    "desc": info.get("_desc", ""),
                    "root": root_key,
                    "fields": fields,
                })

    return {
        "file": target_id,
        "custom": os.path.basename(custom_path) if custom_path else "",
        "has_custom": bool(custom_path and Path(custom_path).exists()),
        "mode": mode,
        "sections": sections,
    }


# ---------------------------------------------------------------------------
# 值转换（界面文本 -> YAML 值）
# ---------------------------------------------------------------------------

def _parse_list_text(
    text: str,
    orig_val: Any = ABSENT,
    base_val: Any = ABSENT,
    key_name: str = "",
) -> Any:
    raw = text.strip()
    original_is_list = isinstance(orig_val, list)
    base_is_list = isinstance(base_val, list)
    force_array = (
        original_is_list
        or base_is_list
        or "format" in key_name
        or "rules" in key_name
    )

    if not raw:
        if orig_val is ABSENT and base_val is ABSENT:
            return ABSENT
        if original_is_list or base_is_list:
            return []
        return None

    safe = _safe_yaml()
    if raw.startswith("[") and raw.endswith("]"):
        try:
            result = safe.load(raw)
            return result if isinstance(result, list) else [result]
        except Exception:
            return [raw]

    if "\n" in raw:
        return [line.strip() for line in raw.splitlines() if line.strip()]
    if "," in raw:
        return [item.strip() for item in raw.split(",") if item.strip()]
    if force_array:
        return [raw]
    try:
        return safe.load(raw)
    except Exception:
        return raw


def coerce_edit_value(
    edit_type: str,
    text: str,
    value: Any,
    display_val: Any,
    base_val: Any,
    key_name: str,
) -> Any:
    if edit_type == "bool":
        return bool(value)
    if edit_type == "select":
        if value in (None, "", "默认/不指定"):
            return ABSENT
        try:
            return int(value)
        except (TypeError, ValueError):
            return value
    if edit_type == "int":
        try:
            return int(str(text).strip())
        except Exception:
            return str(text).strip()
    if edit_type == "float":
        try:
            return float(str(text).strip())
        except Exception:
            return str(text).strip()
    if edit_type == "number":
        number_text = str(text).strip()
        try:
            if re.fullmatch(r"[+-]?\d+", number_text):
                return int(number_text)
            return float(number_text)
        except Exception:
            return number_text
    if edit_type == "multiline_str":
        return text
    if edit_type == "list_text":
        return _parse_list_text(
            text,
            orig_val=display_val,
            base_val=base_val,
            key_name=key_name,
        )
    if edit_type == "raw_yaml":
        txt = text.strip()
        if not txt:
            return None
        safe = _safe_yaml()
        try:
            return safe.load(txt)
        except Exception:
            return [line.strip() for line in txt.splitlines() if line.strip()]
    # str 及其它：保留原始前后空白
    return text


# ---------------------------------------------------------------------------
# 保存
# ---------------------------------------------------------------------------

def _plan_patch_field(
    full_path: str,
    current_val: Any,
    schema_val: Any,
    display_val: Any,
    display_present: bool,
    direct: bool,
) -> Tuple[Dict[str, Any], List[str]]:
    apply: Dict[str, Any] = {}
    remove: List[str] = []

    if not display_present and is_empty(current_val):
        return apply, remove

    if direct:
        if is_really_changed(current_val, schema_val):
            apply[full_path] = current_val
        return apply, remove

    if not is_really_changed(current_val, display_val):
        return apply, remove

    """单字段保存规划，语义对应桌面版 _legacy_save_yaml_config 的字段分支。"""
    special = full_path.endswith("/__patch") or full_path.endswith("/__include")

    if isinstance(current_val, dict):
        schema_dict = schema_val if isinstance(schema_val, dict) else {}
        display_dict = display_val if isinstance(display_val, dict) else {}
        for k, v in current_val.items():
            sub_path = f"{full_path}/{k}"
            if k in ("__include", "__patch"):
                apply[sub_path] = v
            elif is_really_changed(v, schema_dict.get(k)):
                apply[sub_path] = v
            else:
                remove.append(sub_path)
        for k in display_dict:
            if k not in current_val:
                remove.append(f"{full_path}/{k}")
        return apply, remove

    if isinstance(current_val, list):
        if is_really_changed(current_val, schema_val) is False and special:
            remove.append(full_path)
            remove.append(full_path + "/+")
        elif special:
            apply[full_path] = current_val
            remove.append(full_path + "/+")
        elif is_empty(current_val) or not is_really_changed(current_val, schema_val):
            remove.append(full_path)
            remove.append(full_path + "/+")
        else:
            schema_list = schema_val if isinstance(schema_val, list) else []
            n = len(schema_list)
            if len(current_val) >= n and not is_really_changed(current_val[:n], schema_list):
                appended = current_val[n:]
                if appended:
                    apply[full_path + "/+"] = appended
                    remove.append(full_path)
                else:
                    remove.append(full_path)
                    remove.append(full_path + "/+")
            else:
                apply[full_path] = current_val
                remove.append(full_path + "/+")
        return apply, remove

    if special:
        if not is_really_changed(current_val, schema_val):
            remove.append(full_path)
        else:
            apply[full_path] = current_val
    elif is_empty(current_val) or not is_really_changed(current_val, schema_val):
        remove.append(full_path)
    else:
        apply[full_path] = current_val
    return apply, remove


def _apply_direct(engine: RimeYamlEngine, schema_path: Path, apply: Dict[str, Any], remove: List[str]) -> Any:
    if schema_path.exists():
        target_data = engine.load_file(schema_path, default={})
    else:
        target_data = CommentedMap()

    for path, val in apply.items():
        parts = path.split("/")
        curr = target_data
        for i, p in enumerate(parts[:-1]):
            next_p = parts[i + 1]
            if p.startswith("@"):
                idx = int(p[1:])
                while len(curr) <= idx:
                    curr.append(None)
                if curr[idx] is None:
                    curr[idx] = CommentedSeq() if next_p.startswith("@") else CommentedMap()
                curr = curr[idx]
            else:
                if p not in curr:
                    curr[p] = CommentedSeq() if next_p.startswith("@") else CommentedMap()
                curr = curr[p]
        last = parts[-1]
        if last.startswith("@"):
            idx = int(last[1:])
            while len(curr) <= idx:
                curr.append(None)
            _safe_assign(curr, idx, val)
        else:
            _safe_assign(curr, last, val)

    for path in remove:
        parts = path.split("/")
        curr = target_data
        try:
            for p in parts[:-1]:
                curr = curr[int(p[1:])] if p.startswith("@") else curr[p]
            last = parts[-1]
            if last.startswith("@"):
                del curr[int(last[1:])]
            elif last in curr:
                del curr[last]
        except Exception:
            pass
    return target_data


def _set_patch_val(p_dict: Any, path: str, val: Any) -> None:
    if path.endswith("/__patch") or path.endswith("/__include"):
        base_path, op = path.rsplit("/", 1)
        if hasattr(p_dict, "fa"):
            p_dict.fa.set_block_style()
        old_node = p_dict.get(base_path)
        new_node = CommentedMap()
        new_node.fa.set_block_style()
        if isinstance(old_node, Mapping):
            for child_key, child_value in old_node.items():
                if child_key != op:
                    new_node[child_key] = child_value
        force_block = isinstance(val, Sequence) and not isinstance(val, (str, bytes, bytearray))
        new_node[op] = _fresh_yaml_value(val, force_block_sequence=force_block)
        if "__include" in new_node and "__patch" in new_node:
            ordered = CommentedMap()
            ordered.fa.set_block_style()
            ordered["__include"] = new_node["__include"]
            ordered["__patch"] = new_node["__patch"]
            for child_key, child_value in new_node.items():
                if child_key not in ordered:
                    ordered[child_key] = child_value
            new_node = ordered
        p_dict[base_path] = new_node
        if path in p_dict:
            del p_dict[path]
        return

    parts = path.split("/")
    if path.endswith("/+"):
        parts = parts[:-2] + [parts[-2] + "/+"]
    for i in range(1, len(parts)):
        parent_path = "/".join(path.split("/")[:i])
        if parent_path in p_dict and isinstance(p_dict[parent_path], dict):
            node = p_dict[parent_path]
            sub_parts = parts[i:]
            for part in sub_parts[:-1]:
                if part not in node or not isinstance(node[part], dict):
                    node[part] = {}
                node = node[part]
            _safe_assign(node, sub_parts[-1], val)
            return
    _safe_assign(p_dict, path, val)


def _del_patch_val(p_dict: Any, path: str) -> None:
    if path.endswith("/__patch") or path.endswith("/__include"):
        base_path, op = path.rsplit("/", 1)
        if base_path in p_dict and isinstance(p_dict[base_path], dict):
            if op in p_dict[base_path]:
                del p_dict[base_path][op]
                if not p_dict[base_path]:
                    del p_dict[base_path]
        if path in p_dict:
            del p_dict[path]
        return
    if path in p_dict:
        del p_dict[path]
        return
    parts = path.split("/")
    if path.endswith("/+"):
        parts = parts[:-2] + [parts[-2] + "/+"]
    for i in range(1, len(parts)):
        parent_path = "/".join(path.split("/")[:i])
        if parent_path in p_dict and isinstance(p_dict[parent_path], dict):
            node = p_dict[parent_path]
            sub_parts = parts[i:]
            try:
                for p in sub_parts[:-1]:
                    node = node[p]
                del node[sub_parts[-1]]
            except Exception:
                pass
            return


def _fresh_yaml_value(value: Any, force_block_sequence: bool = False) -> Any:
    if isinstance(value, Mapping):
        result = CommentedMap()
        result.fa.set_block_style()
        for child_key, child_value in value.items():
            result[child_key] = _fresh_yaml_value(child_value)
        return result
    if isinstance(value, Sequence) and not isinstance(value, (str, bytes, bytearray)):
        result = CommentedSeq(_fresh_yaml_value(v) for v in value)
        if force_block_sequence:
            result.fa.set_block_style()
        return result
    return value


DYNAMIC_TYPES = frozenset({
    "dynamic_list", "dynamic_map", "dynamic_kv_list", "dynamic_block_list",
    "schema_checkboxes", "algebra_patch", "reverse_algebra", "english_algebra",
    "mixed_algebra",
})


def _coerce_block_list(
    node: Any,
    edit: Mapping[str, Any],
) -> Any:
    template = (node or {}).get("template", {}) or {}
    result = []
    safe = _safe_yaml()
    for row in edit.get("rows") or []:
        original = row.get("original") or {}
        if not isinstance(original, Mapping):
            original = {}
        original = dict(original)
        fields = row.get("fields") or {}
        block = CommentedMap()
        for key, info in template.items():
            if key not in fields:
                # 隐藏字段：保持原始值不动
                if key in original:
                    block[key] = _fresh_yaml_value(original[key])
                continue
            field = fields.get(key) or {}
            field_present = key in original
            original_value = original.get(key) if field_present else ABSENT
            field_type = info.get("type", "str")
            val: Any = ABSENT

            if field_type == "action_kv":
                for pk in (info.get("preset_keys") or {}):
                    block.pop(pk, None)
                action_key = field.get("action_key")
                action_value = field.get("action_value")
                if action_key and action_value not in (None, ""):
                    block[action_key] = str(action_value)
                continue

            if field_type == "bool":
                tri = field.get("tri", -1)
                if tri == -1:
                    continue
                val = tri == 1
            elif field_type == "select":
                selected = str(field.get("text", "")).strip()
                if not selected or selected == "默认/不指定":
                    val = ABSENT
                elif isinstance(original_value, bool):
                    val = selected.lower() == "true"
                else:
                    val = selected
            elif field_type == "list_text":
                raw_text = str(field.get("text", ""))
                if not raw_text.strip() and not field_present:
                    block.pop(key, None)
                    continue
                val = _parse_list_text(
                    raw_text, orig_val=original_value, base_val=original_value, key_name=key
                )
            elif field_type == "raw_yaml":
                raw_text = str(field.get("text", "")).strip()
                if not raw_text:
                    val = ABSENT
                else:
                    try:
                        val = safe.load(raw_text)
                    except Exception:
                        val = raw_text
            else:
                raw_text = str(field.get("text", "")).strip()
                if not raw_text:
                    val = ABSENT
                else:
                    try:
                        val = safe.load(raw_text)
                    except Exception:
                        val = raw_text

            if val is ABSENT:
                block.pop(key, None)
                continue
            block[key] = smart_seq(val)

        meaningful = False
        for value in block.values():
            if value is None or value == "":
                continue
            if isinstance(value, (list, dict)) and not value:
                continue
            meaningful = True
            break
        if block and (original or meaningful):
            result.append(block)
    return result


def _coerce_dynamic(
    target_id: str,
    edit_type: str,
    edit: Mapping[str, Any],
    display_val: Any,
    node: Any = None,
) -> Any:
    if edit_type == "dynamic_block_list":
        return _coerce_block_list(node, edit)

    if edit_type == "dynamic_list":
        values = []
        for row in edit.get("rows") or []:
            text = str(row.get("text", "")).strip()
            if text:
                values.append(text)
        return values

    if edit_type == "dynamic_map":
        safe = _safe_yaml()
        result = {}
        for row in edit.get("rows") or []:
            text = str(row.get("text", ""))
            if ":" not in text:
                continue
            key_text, value_text = text.split(":", 1)
            key = key_text.strip()
            if not key:
                continue
            if value_text.startswith(" "):
                value_text = value_text[1:]
            if value_text == "":
                result[key] = ""
                continue
            try:
                parsed = safe.load(value_text)
                result[key] = value_text if parsed is None else parsed
            except Exception:
                result[key] = value_text
        return result

    if edit_type == "dynamic_kv_list":
        result = {}
        for row in edit.get("rows") or []:
            key = row.get("key")
            value = row.get("value")
            if not key or not value:
                continue
            desc = str(row.get("desc", ""))
            lowered = desc.lower()
            if "(true/false)" in lowered:
                value = str(value).lower() == "true"
            elif "数字" in desc:
                try:
                    value = int(value)
                except (TypeError, ValueError):
                    pass
            elif "填列表" in desc or "数组" in desc:
                value = _parse_list_text(str(value), orig_val=[], base_val=[], key_name=str(key))
            result[key] = value
        return result

    if edit_type == "schema_checkboxes":
        schemas = edit.get("schemas") or []
        return [{"schema": str(s)} for s in schemas if str(s)]

    if edit_type == "algebra_patch":
        comp = edit.get("algebra") or {}
        variant = _algebra_variant(target_id)
        scheme = str(comp.get("scheme") or "全拼")
        items = [f"wanxiang_algebra:/{variant}/{scheme}"]
        if variant == "pro":
            items.append("wanxiang_algebra:/pro/" + str(comp.get("aux") or "直接辅助"))
        if comp.get("tiquan"):
            items.append(f"wanxiang_algebra:/{scheme}提权")
        for path in comp.get("fuzzy") or []:
            items.append(str(path))
        for line in comp.get("extras") or []:
            text = str(line).strip()
            if text:
                items.append(text)
        original = [str(x).strip() for x in display_val if str(x).strip()] if isinstance(display_val, list) else []
        return sort_algebra_patch_items(items, original)

    if edit_type == "reverse_algebra":
        comp = edit.get("algebra") or {}
        result = CommentedMap()
        result["__include"] = f"wanxiang_algebra:/reverse/{comp.get('scheme') or '自然码'}"
        result["__patch"] = f"wanxiang_algebra:/reverse/{comp.get('stroke') or 'hspzn'}"
        return result

    if edit_type in ("english_algebra", "mixed_algebra"):
        comp = edit.get("algebra") or {}
        if edit_type == "english_algebra":
            base = "通用规则"
            scheme = comp.get("scheme") or "自然码"
            namespace = "english"
        else:
            base = "通用派生规则"
            scheme = comp.get("scheme") or "全拼"
            namespace = "mixed"
        result = CommentedMap()
        result["__include"] = f"wanxiang_algebra:/{namespace}/{base}"
        result["__patch"] = f"wanxiang_algebra:/{namespace}/{scheme}"
        return result

    return ABSENT


def save_document(
    engine: RimeYamlEngine,
    workspace: str,
    target_id: str,
    mode: str,
    edits: List[Dict[str, Any]],
) -> Dict[str, Any]:
    """按桌面版语义保存：edits 为 [{path, type, text?, value?}]。"""
    workspace_path = Path(workspace)
    schema_path = workspace_path / target_id
    if not schema_path.exists():
        raise FileNotFoundError(f"文件不存在：{target_id}")
    direct = mode == "direct"
    custom_path = _custom_path_for(engine, schema_path)

    schema_data = engine.load_file(schema_path, default={})
    patch_data = (
        engine.load_file(custom_path, default={})
        if custom_path and Path(custom_path).exists()
        else CommentedMap()
    )
    patch = patch_data.get("patch", {}) if isinstance(patch_data, Mapping) else {}
    source_effective = engine.apply_patch(schema_data, patch)
    try:
        effective = engine.compile_file(schema_path, auto_custom=True)
    except Exception:
        effective = source_effective

    path_meta = _path_meta(target_id, schema_data, effective)

    apply: Dict[str, Any] = {}
    remove: List[str] = []

    for edit in edits:
        path = str(edit.get("path", "")).strip()
        if not path:
            continue
        meta = path_meta.get(path)
        edit_type = str(edit.get("type") or (meta or {}).get("type") or "str")
        is_directive = (
            (meta or {}).get("baseline") == "source_effective"
            or path.endswith("/__patch")
            or path.endswith("/__include")
            or edit_type in ("algebra_patch", "reverse_algebra", "english_algebra", "mixed_algebra")
        )
        display_tree = source_effective if (not direct and is_directive) else effective
        schema_val = engine.get_path(schema_data, path, ABSENT)
        display_val = engine.get_path(display_tree, path, ABSENT)
        display_present = display_val is not ABSENT
        base_val = None if schema_val is ABSENT else schema_val

        if edit_type in DYNAMIC_TYPES:
            current_val = _coerce_dynamic(target_id, edit_type, edit, display_val, (meta or {}).get("node"))
        elif edit_type in ("bool", "select"):
            current_val = coerce_edit_value(
                edit_type, "", edit.get("value"), display_val, base_val, path.split("/")[-1]
            )
        else:
            current_val = coerce_edit_value(
                edit_type,
                str(edit.get("text", "")),
                None,
                display_val,
                base_val,
                path.split("/")[-1],
            )

        if current_val is ABSENT:
            continue
        if isinstance(current_val, list):
            current_val = smart_seq(current_val)

        field_apply, field_remove = _plan_patch_field(
            path, current_val, schema_val, display_val, display_present, direct
        )
        apply.update(field_apply)
        remove.extend(field_remove)

    if not apply and not remove:
        return {"changed": [], "summary": "界面值与当前配置一致，未生成写入内容。"}

    changed_files: List[str] = []
    yaml = YAML()
    yaml.preserve_quotes = True
    yaml.width = 1024
    yaml.indent(mapping=2, sequence=4, offset=2)

    if direct:
        target_data = _apply_direct(engine, schema_path, apply, remove)
        engine.atomic_write_many({schema_path: target_data})
        changed_files.append(target_id)
        summary = f"[直写] {target_id}：请求更新 {len(apply)} 项"
    else:
        custom_data = patch_data if isinstance(patch_data, Mapping) else CommentedMap()
        if "patch" not in custom_data or custom_data["patch"] is None:
            custom_data["patch"] = CommentedMap()
        for p, v in apply.items():
            _set_patch_val(custom_data["patch"], p, v)
        for p in remove:
            _del_patch_val(custom_data["patch"], p)
        if "patch" not in custom_data or custom_data["patch"] is None:
            custom_data["patch"] = CommentedMap()
        if hasattr(custom_data, "fa"):
            custom_data.fa.set_block_style()
        if hasattr(custom_data["patch"], "fa"):
            custom_data["patch"].fa.set_block_style()
        custom_name = os.path.basename(custom_path)
        engine.atomic_write_many({Path(custom_path): custom_data})
        changed_files.append(custom_name)
        summary = f"[补丁] {custom_name}：请求更新 {len(apply)} 项，清理 {len(remove)} 项"

    return {"changed": changed_files, "summary": summary}
