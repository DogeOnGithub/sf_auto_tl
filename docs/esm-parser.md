# ESM 解析器 - 可翻译子记录判断规则

## 判断机制

ESM 解析器使用两层匹配来判断子记录是否包含可翻译文本：

1. **通用子记录类型**（`TRANSLATABLE_SUBRECORD_TYPES`）：无论在哪种记录类型下都需要翻译
2. **记录类型 + 子记录类型组合**（`TRANSLATABLE_COMBINATIONS`）：只在特定记录类型下才需要翻译

匹配逻辑：满足任一条件即提取文本。但如果 (记录类型, 子记录类型) 在排除覆盖列表 `NON_TRANSLATABLE_OVERRIDES` 中，则即使子记录类型在通用列表中也不提取。

## 通用可翻译子记录类型

| 子记录类型 | 含义 |
|-----------|------|
| `FULL` | 显示名称（物品、NPC、地点等） |
| `DESC` | 描述文本 |
| `NNAM` | 任务目标文本 |
| `SHRT` | 短名称 |
| `RNAM` | 备注/标记文本 |

注意：`TNAM` 已从通用列表移除，因为在 `NPC_` 记录下是皮肤贴图名（不需要翻译）。

## 排除覆盖规则（NON_TRANSLATABLE_OVERRIDES）

以下组合虽然子记录类型在通用列表中，但在特定记录类型下包含二进制数据，必须排除：

| 记录类型 | 子记录类型 | 原因 |
|---------|-----------|------|
| `NPC_` | `RNAM` | NPC 种族 FormID 引用（4 字节二进制），被误翻译会导致游戏闪退 |
| `NPC_` | `NNAM` | NPC 二进制数据（非任务目标文本） |
| `SMQN` | `NNAM` | Story Manager Quest Node 二进制数据 |
| `FURN` | `NNAM` | 家具二进制数据（非文本） |
| `RSPJ` | `RNAM` | 研究项目前置依赖 FormID 引用（4 字节二进制），误翻译会破坏研究树 |

## 组合匹配规则

| 记录类型 | 子记录类型 | 含义 | 数据来源 |
|---------|-----------|------|---------|
| `INFO` | `NAM1` | NPC 对话文本 | 对话记录 |
| `QUST` | `CNAM` | 任务日志描述 | 任务记录 |
| `QUST` | `NAM2` | 任务阶段文本 | 任务记录 |
| `TMLM` | `ITXT` | 终端菜单选项 | 终端菜单记录 |
| `TMLM` | `BTXT` | 终端正文内容 | 终端菜单记录 |
| `TMLM` | `UNAM` | 终端操作结果 | 终端菜单记录 |
| `NPC_` | `LNAM` | NPC 所属组织名 | NPC 记录 |
| `NPC_` | `ATTX` | 交互提示文本 | NPC 记录 |
| `FURN` | `ATTX` | 家具交互提示文本（如 Drive、Sit） | 家具记录 |
| `REFR` | `UNAM` | 地图标记名 | 引用记录 |
| `MESG` | `ITXT` | 消息框按钮文本 | 消息记录 |
| `PERK` | `EPF2` | Perk 效果描述文本 | Perk 记录 |
| `BOOK` | `CNAM` | 书籍正文内容 | 书籍记录 |
| `MGEF` | `DNAM` | 魔法效果描述 | 魔法效果记录 |
| `LVLN` | `ONAM` | 等级列表 NPC 覆盖名称 | 等级列表记录 |
| `GPOF` | `DNAM` | 游戏设置选项描述 | 游戏设置选项记录 |
| `GPOF` | `VOVS` | 游戏设置选项值描述 | 游戏设置选项记录 |
| `AVIF` | `NLDT` | Actor Value 长描述文本 | Actor Value 记录 |
| `QUST` | `QMDP` | 任务标记显示参数 | 任务记录 |
| `QUST` | `QMDT` | 任务标记显示标题 | 任务记录 |
| `QUST` | `QMSU` | 任务摘要文本 | 任务记录 |

## 已排除的类型（不需要翻译）

| 子记录类型 | 原因 |
|-----------|------|
| `EDID` | 编辑器 ID，内部标识符 |
| `MODL` | 3D 模型文件路径 |
| `BFCB` | 组件类名 |
| `VMAD` | 脚本数据 |
| `ANAM/BNAM/CNAM`（非 QUST） | 动画/骨骼路径 |
| `VNAM/QNAM` | 贴图路径/皮肤名 |
| `BMPN/FMRG` | NPC 面部形态参数 |
| `HCOL/BCOL/ECOL/FHCL/JCOL` | 颜色枚举值 |
| `TETC` | 牙齿贴图名 |
| `ALID` | 任务别名 ID |
| `SNAM`（DIAL 下） | 对话类型标识 |
| `NAM0`（INFO/SCEN 下） | 对话/场景分支标签（编辑器用） |

## 验证工具

使用 `starfield-engine/tools/scan_subrecords.py` 扫描 ESM 文件，发现新的可翻译子记录类型：

```bash
cd starfield-engine
python3 -m tools.scan_subrecords <esm_file_path>
```

输出分为三类：
- `TRANSLATE`：自动判定需要翻译
- `REVIEW`：需要人工确认
- `SKIP`：自动判定不需要翻译

## 修改指南

- 新增通用类型：加到 `TRANSLATABLE_SUBRECORD_TYPES`
- 新增组合规则：加到 `TRANSLATABLE_COMBINATIONS`，格式 `(b"RECORD_TYPE", b"SUBRECORD_TYPE")`
- 新增排除覆盖：加到 `NON_TRANSLATABLE_OVERRIDES`，用于通用类型在特定记录下包含二进制数据的情况
- 新增 Object Template 记录类型：加到 `OBJECT_TEMPLATE_RECORD_TYPES`
- 修改后用 scan_subrecords.py 验证效果

## Object Template 保护机制

WEAP、ARMO、NPC_ 等记录类型支持 Object Template 系统。这些记录中 `OBTE` 子记录标记了模板区域的开始，之后的 `FULL` 子记录是模板名称（如 `Fallback`、`Standard Low`、`Standard High` 等），引擎用这些名称匹配模板层级。

**翻译这些模板名称会导致引擎无法匹配模板，工作台/改装界面无法正常显示。**

解析器和写入器通过 `OBJECT_TEMPLATE_RECORD_TYPES` 和 `in_object_template` 标记来保护这些名称不被提取和翻译。只有 OBTE 之前的第一个 FULL（即物品显示名称）会被翻译。
