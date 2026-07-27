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
| `PERK` | `RNAM` | Perk 打包二进制数据（3 字节，非文本）。高位字节经 Windows-1252 兜底解码成"可打印"字符后被误翻译，回写时按 UTF-8 编码使字节翻倍（如 `b8`→`c2 b8`），破坏 perk 导致技能树在游戏内不显示 |

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
| `AMMO` | `ONAM` | 弹药类型短名称 | 弹药记录 |
| `ACTI` | `ATTX` | 激活器交互提示文本（如 Use） | 激活器记录 |
| `FLOR` | `ATTX` | 植物采集提示文本（如 Harvest） | 植物记录 |
| `BOOK` | `ENAM` | 书籍效果名称 | 书籍记录 |

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

WEAP、ARMO、NPC_ 等记录类型支持 Object Template 系统。这些记录中 `OBTE` 子记录标记了模板区域的开始，之后的 `FULL` 子记录可能是：

1. **引擎模板层级名**（如 `Fallback`、`Default`、`Entry`、`Standard Low/Med/High` 等）— 翻译会导致工作台崩溃，必须跳过
2. **自定义显示名**（如 mod 武器名 `Headhunter's Pistol`）— 游戏中会显示给玩家，需要翻译

解析器通过 `OBJECT_TEMPLATE_LEVEL_NAMES` 集合逐条判断 OBTE 区域内的 FULL 文本：
- 文本匹配层级名集合 → 跳过
- 文本不匹配 → 提取翻译

已知的引擎模板层级名：`Fallback`、`Default`、`Entry`、`Standard`、`Standard Low/Med/High/Very High`、`Auto Low/Med/High`、`Upgraded Low/Med/High`、`Silenced Low/Med/High`、`Sniper Low/Med/High`、`Simple`。

如果发现新的模板层级名导致翻译后崩溃，加到 `OBJECT_TEMPLATE_LEVEL_NAMES` 中。

## 开启本地化的 mod（Strings 文件）

开启本地化（TES4 头 flags bit 7 `0x80`）的 ESM 会把 FULL/DESC 等可翻译文本移到外部 Strings 文件（`.STRINGS`/`.DLSTRINGS`/`.ILSTRINGS`），ESM 内仅保留 4 字节字符串 ID 引用。这类 mod 不走 ESM 子记录解析，而是由 `strings_parser.py` / `strings_writer.py` 直接解析和回写 Strings 文件（不触碰 ESM）。

### Strings 文件格式（小端）

- Header：`uint32 count` + `uint32 data_size`（数据区字节数）
- 目录：`count × (uint32 string_id, uint32 offset)`，offset 相对数据区起点（数据区起点 = 8 + count×8）
- 数据区：
  - `.STRINGS`：每条为 null 结尾的原始字符串，无长度前缀
  - `.DLSTRINGS`/`.ILSTRINGS`：每条为 `uint32 长度`（含 null）+ 字符串字节 + null

编码固定 UTF-8。空文件（count=0）为合法 8 字节头。

### record_id 约定

采用三段式 `FILETYPE:id:sub_tag`（如 `STRINGS:5:STR`、`DLSTRINGS:10:DL`、`ILSTRINGS:100:IL`），与 ESM 的 `RECORD_TYPE:FORM_ID:SUBRECORD_TYPE` 对齐，从而复用去重、缓存、确认记录等既有逻辑。回写时保留全部 string_id 与顺序，仅替换文本、重算 offset/data_size；未翻译到的条目按原文保留。
