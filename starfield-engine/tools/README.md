# ESM MOD 修复工具集

## 背景

Starfield MOD 的 ESM 文件中，如果 MOD 作者在 Creation Kit 中将大量 REFR（物品引用）错误地放置在 Worldspace 的 Persistent Cell Children GRUP 中（而非 Temporary Cell Children），会导致这些物品被引擎始终加载，不随距离卸载，表现为杂物（垃圾桶、盘子等）在所有场景凭空出现。

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

保留条件（满足任一则不移动）：
- 非 REFR 类型（ACHR 等角色引用）
- 有 Initially Disabled 标志（脚本控制的物品）
- Base FormID 以 01 开头（MOD 自身定义的物品）

注意：当前 `fix_persistent.py` 中的 `TARGET_CELLS` 是针对 forgottenfrontierspoi2.esm 硬编码的。修复其他 MOD 时需要先用 `analyze_persistent.py` 找出异常 Cell，再修改 `TARGET_CELLS`。

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

## 典型修复流程

```bash
cd starfield-engine

# 1. 诊断：分析 Persistent 结构，找出异常 Cell
python -m tools.analyze_persistent problem_mod.esm

# 2. 辅助：查看 Worldspace 和 POI 名称
python -m tools.list_worldspaces problem_mod.esm
python -m tools.list_map_markers problem_mod.esm

# 3. 修复：修改 fix_persistent.py 中的 TARGET_CELLS 后执行
python -m tools.fix_persistent problem_mod.esm problem_mod_fixed.esm

# 4. 验证：确认修复结果
python -m tools.analyze_persistent problem_mod_fixed.esm
python -m tools.list_kept_persistent problem_mod_fixed.esm
```
