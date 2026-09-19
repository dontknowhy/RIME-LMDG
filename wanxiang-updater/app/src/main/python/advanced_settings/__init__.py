"""Android 端高级设置：仅导出与 UI 无关的 YAML 引擎与编辑逻辑。

桌面版的 mixin.py / widgets.py（PySide6）与 deployment.py（桌面 IM 部署）
不参与 Android 打包，避免引入 Qt 与桌面子系统依赖。
"""

from .core import (
    ConflictSeverity,
    KeyClaim,
    KeyConflict,
    KeySpec,
    LiveKeyRegistry,
    LoadedRimeDocument,
    RimeKeyConflictEngine,
    RimeYamlEngine,
    RimeYamlError,
    SaveTransaction,
    YamlDuplicateIssue,
    is_managed_config_yaml,
    is_managed_source_yaml,
    is_rime_dictionary,
)

__all__ = [
    "ConflictSeverity",
    "KeyClaim",
    "KeyConflict",
    "KeySpec",
    "LiveKeyRegistry",
    "LoadedRimeDocument",
    "RimeKeyConflictEngine",
    "RimeYamlEngine",
    "RimeYamlError",
    "SaveTransaction",
    "YamlDuplicateIssue",
    "is_managed_config_yaml",
    "is_managed_source_yaml",
    "is_rime_dictionary",
]
