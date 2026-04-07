# ESM MOD 修复工具集

## 背景

Starfield MOD 的 ESM 文件中，如果 MOD 作者在 Creation Kit 中将大量 REFR（物品引用）错误地放置在 Worldspace 的 Persistent Cell Children GRUP 中（而非 Temporary Cell Children），可能导致引擎异常，表现为杂物（垃圾桶、盘子等）在其他 Worldspace 凭空出现。

### 问题分析（forgottenfrontierspoi2.esm）

该 MOD 包含 6 个 LandscapeCutWorldSpace 类型的 overlay Worldspace（POI），5 个 Worldspace 的 Persistent Cell 中所有 REFR 都在 Persistent Children 里，Temporary 为零：

| POI | Persistent | Temporary |
|-----|-----------|-----------|
| COCOPOI08Small | 4508 | 0 |
| COCOPOI10 | 2263 | 0 |
| COCOPOI12 | 1366 | 0 |
| COCOPOI9 | 282 | 0 |
| COCOPOI11 | 199 | 0 |

总计 8618 条 Persistent REFR，其中 8568 条引用原版 base object，50 条引用 MOD 自定义 base object。

MOD 本身没有修改任何原版 Worldspace，也没有在原版 Worldspace 中放置 REFR。但过多的 Persistent REFR 可能触发引擎异常，导致杂物在主世界（如亚特兰蒂斯太空港）凭空出现。

### 修复策略

通过扫描 REFR 内部子记录，精确区分功能性 REFR 和纯装饰物：

**保留在 Persistent 的 REFR**（满足任一条件）：
- 非 REFR 类型（ACHR 等角色引用）
- 有 Initially Disabled 标志
- Base FormID 是 MOD 自定义的（01 开头）
- 有 EDID（Editor ID，MOD 作者有意命名的关键物品）
- 有逻辑功能性子记录：VMAD（脚本）、XESP（Enable Parent）、XLKR（Linked Ref）、XTEL（传送）等
- 有渲染功能性子记录：XRFG（预合并渲染组）、XLYR（图层）、XLMS、XGDS 等

**移到 Temporary 的 REFR**：
- 只有基础子记录（NAME + DATA）的纯装饰物，无任何渲染系统或逻辑功能关联

修复后效果（forgottenfrontierspoi2.esm）：
- 保留 Persistent: 5246 条
- 移到 Temporary: 3377 条

## ESM 文件结构要点

```
GRUP (Top-Level: WRLD)
  WRLD 记录 (Worldspace 定义)
  GRUP (World Children)
    CELL 记录 (Persistent Cell，每个 Worldspace 有且只有一个)
    GRUP (Cell Children)
      GRUP (Cell Persistent Children, type=8)  ← 始终加载，不随距离卸载
        REFR / ACHR ...
      GRUP (Cell Temporary Children, type=9)   ← 按距离加载/卸载
        REFR / ACHR ...
    GRUP (Exterior Cell Block)
      GRUP (Exterior Cell Sub-Block)
        CELL 记录 (普通 Exterior Cell)
        GRUP (Cell Children)
          GRUP (Cell Temporary Children)
            REFR ...
```

- Persistent Children (GRUP type=8): 记录始终加载，用于任务标记、NPC 锚点等关键物品
- Temporary Children (GRUP type=9): 记录按玩家距离动态加载/卸载，普通场景物品应放在这里
- Interior Cell: 独立室内空间，需过门加载
- Exterior Cell: 大世界网格块，按距离自动加载

## 工具列表

所有工具在 `starfield-engine/` 目录下运行：

### 1. scan_refr.py — 扫描 REFR 记录

列出 ESM 中所有 REFR 记录及其引用的 Base Object，按出现次数排序。

```bash
python -m tools.scan_refr <esm_file>
```

### 2. analyze_refr_placement.py — 分析 REFR 放置结构

诊断 REFR 的 Persistent/Temporary 分布、坐标异常、GRUP 层级结构。

```bash
python -m tools.analyze_refr_placement <esm_file>
```

### 3. analyze_persistent.py — 深入分析 Persistent 归属

区分 Interior/Exterior Cell，按 Worldspace 分组，找出全 Persistent 的异常 Cell。

```bash
python -m tools.analyze_persistent <esm_file>
```

### 4. list_worldspaces.py — 列出 Worldspace 信息

显示所有 Worldspace 的 FormID、EDID、FULL 显示名称。

```bash
python -m tools.list_worldspaces <esm_file>
```

### 5. list_map_markers.py — 列出地图标记和 Location

提取 POI 的实际名称（从 Location 记录的 FULL 子记录）。

```bash
python -m tools.list_map_markers <esm_file>
```

### 6. count_cells.py — 统计 Cell 分布

统计 Interior/Exterior Cell 数量，按 Worldspace 分组。

```bash
python -m tools.count_cells <esm_file>
```

### 7. fix_persistent.py — 修复 Persistent REFR（核心修复工具）

将 Worldspace Persistent Cell 中不需要持久化的 REFR 移到 Temporary Cell Children。

```bash
python -m tools.fix_persistent <input_esm> <output_esm>
```

通过扫描子记录精确判断保留/移动，详见上方"修复策略"。

注意：`TARGET_CELLS` 是针对 forgottenfrontierspoi2.esm 硬编码的。修复其他 MOD 时需要先用 `analyze_persistent.py` 找出异常 Cell，再修改 `TARGET_CELLS`。

### 8. list_kept_persistent.py — 查看保留的 Persistent 记录

验证修复结果，列出修复后仍保留在 Persistent 中的记录及保留原因。

```bash
python -m tools.list_kept_persistent <fixed_esm>
```

### 9. remove_refr.py — 按 Base FormID 删除 REFR

根据指定的 Base FormID 列表，从 ESM 中删除对应的 REFR 记录。

```bash
python -m tools.remove_refr <input_esm> <output_esm> <base_formid1> [base_formid2 ...]
python -m tools.remove_refr <input_esm> <output_esm> --file <formid_list_file>
```

### 10. analyze_base_objects.py — 分析 Base Object 分布

分析 Persistent Cell 中 REFR 引用的 Base Object 分布，按数量排序，显示子记录类型。

```bash
python -m tools.analyze_base_objects <esm_file>
```

### 11. scan_vanilla_refs.py — 扫描原版记录引用

检查 MOD 是否覆盖原版记录、是否在原版 Worldspace 中放置 REFR。

```bash
python -m tools.scan_vanilla_refs <esm_file>
```

## 典型修复流程

```bash
cd starfield-engine

# 1. 诊断：分析 Persistent 结构，找出异常 Cell
python -m tools.analyze_persistent problem_mod.esm

# 2. 深入分析：查看 Base Object 分布和子记录特征
python -m tools.analyze_base_objects problem_mod.esm

# 3. 排查：检查是否修改了原版记录
python -m tools.scan_vanilla_refs problem_mod.esm

# 4. 辅助：查看 Worldspace 和 POI 名称
python -m tools.list_worldspaces problem_mod.esm
python -m tools.list_map_markers problem_mod.esm

# 5. 修复：修改 fix_persistent.py 中的 TARGET_CELLS 后执行
python -m tools.fix_persistent problem_mod.esm problem_mod_fixed.esm

# 6. 验证：确认修复结果
python -m tools.analyze_persistent problem_mod_fixed.esm
python -m tools.list_kept_persistent problem_mod_fixed.esm
```
