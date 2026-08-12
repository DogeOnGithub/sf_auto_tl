# 待办项

记录已确认但尚未处理的问题。每项都附了实测证据，避免下次重新调研。

数据来源：2026-08-11 / 08-12 用 `Hal_SkillTree_Background_Trait_Overhaul.esm`（1146 词条）在本地跑的两次完整翻译，以及线上库的存量数据。

---

## 1. 去重键带 subrecord_type，导致同一段英文被译成两种中文

**优先级**：高。这是唯一会让玩家在游戏里直接看到问题的一项。

**现象**：`translator.py` 的去重键是 `(sub_type, text)`，同一段英文出现在两个不同子记录字段时被当成两条分别翻译，落进不同批次后模型可能给出不同译法。

```python
key = (sub_type, r.text)   # sub_type 取 record_id 最后一段，如 NAM2 / CNAM / FULL / DESC
```

**证据**（task `ffcbe0b2-4b3f-4743-9114-09e399dc606e`，902 条缓存条目）：

| 指标 | 数值 |
|------|------|
| 按文本去重后 | 818 条 |
| 重复翻译 | 84 条（9.3%） |
| 其中译文不一致 | 2 条 |

```
Robotics Rank 3 Refund   QUST:NAM2 → 机器人学等级3退款
Robotics Rank 3 Refund   QUST:CNAM → 机器人学等级3返还
Scanning Rank 2 Refund   QUST:NAM2 → 扫描等级2返还
Scanning Rank 2 Refund   QUST:CNAM → 扫描等级2退款
```

全库 91 组重复中 3 组不一致，另一例是 `STRINGS:STR` 与 `DLSTRINGS:DL` 的「你 / 您」分歧。

**为什么最近变严重**：批次上限从 800 条降到 80 条后，同一段文本的两个副本几乎必然落进不同批次。以前 895 条只切 2 批，撞进同一批的概率约 50%；现在 12 批，降到约 8%。bug 一直存在，缩小批次把它从偶发放大成稳定复现。术语表兜不住，因为 `Refund` 这类词不一定被当作专有名词提取出来。

**建议**：去重键去掉 `sub_type`，只按原文去重。收益是双份的——少约 9.3% 的 LLM 调用，且同文同译从靠运气变成结构性保证。

**待确认的取舍**：改完之后同一段英文在不同子记录类型里只会有一种译法，失去「按上下文区分译法」的能力。实测 84 组里 82 组本来就译得一样，这种需求基本不存在，但属于领域判断。

**不要动缓存键**：`(record_type, subrecord_type, source_text_hash, target_lang)` 保留按类型存多份的能力，改它要迁移数据和重建唯一索引，不划算。去重键改了之后那几份缓存行内容自然就一致了。

---

## 2. 思维链吃掉了约 87% 的输出 token

**优先级**：高。直接决定翻译成本和耗时。

**证据**：同一个 mod、同样 1146 词条、同样去重到 895、同样 12 批、两次都是缓存 0 命中，基准一致：

| | 请求 | 输入 | 输出 | 思维链 | 译文 | 思维链占比 | 输出/请求 | 翻译耗时 |
|---|---|---|---|---|---|---|---|---|
| 08-11 | 12 | 22507 | 79336 | 63205 | 16131 | 79.7% | 6611 | 7.5 分 |
| 08-12 | 12 | 22434 | 126315 | 110709 | 15606 | 87.6% | 10526 | 14 分 |

输入 -0.3%、译文输出 -3.3%，但思维链 +75%、耗时翻倍。差异全部在思维链这个不受我们控制的部分，也就是说**输出费用无法预估**。

当前用的是代码默认模型 `deepseek-v4-flash`（`llm_model` 字段为空），它默认开思维链。

**建议**：查 DeepSeek 关闭思维链的方式（换模型名，或 body 里传 `thinking` 之类的字段）。按 08-12 的数据，关掉后输出 token 从 126315 降到 15606 左右。

**落地位置**：`llm_client._completion_kwargs()` 就是为这类可选参数留的口子，加一个 `LLM_EXTRA_BODY` 环境变量即可，不用改调用逻辑。

---

## 3. 批次大小的换算系数没有计入思维链

**优先级**：中。当前 provider 输出上限够高，暂时不触发。

`DEFAULT_MAX_BATCH_CHARS` 由 `OUTPUT_TOKEN_BUDGET / 0.4` 反推，其中 0.4 只覆盖**译文**输出。实测含思维链后是每字符约 1.4 token（08-12 那次每请求 10526 输出 token）。

DeepSeek 给该模型的输出上限远高于此，所以两次跑都是 `missing 0`、拆分预算一次没动。但换到默认输出上限 4096 的 provider，每批会持续撞上限、反复触发拆分，白付一倍请求。

**处理顺序**：和第 2 项绑定。关掉思维链后 0.4 这个系数就是准的，不需要改；不关的话要把注释和默认值按 1.4 重算（批次会降到约 2300 字符，请求数涨 3 倍，不划算）。

---

## 4. confirmation 模式的 sync_timeout 任务无法复活

**优先级**：中。

`shouldDropTerminalCallback` 刻意不复活 confirmation 模式的任务：判死时确认记录已被 `cleanupConfirmationRecords` 清掉，复活成 `pending_confirmation` 只会给出一个空的确认列表，比明确失败更让人困惑。

要支持这种模式的复活，得把确认记录的回收也改成延后。但目前没有任何定时任务会回收 `failed` 任务的确认记录（`cleanupExpiredTasks` 只覆盖 `completed` 和 `pending_confirmation`），直接改成延后就会漏行。

**建议**：先补一个按时间回收 failed 任务确认记录的清理逻辑，再放开 confirmation 模式的复活。

---

## 5. 引擎任务状态存在进程内存，worker 重启即丢

**优先级**：中。

`Translator._tasks` 是进程内 dict，gunicorn 单 worker 被 OOM 或异常重启后全部任务态丢失，翻译线程（daemon）一起死。

现在靠 API 侧的 404 快速判死兜住了用户可见症状：30 秒内给出「引擎中已无该任务的状态」并回收磁盘，而不是等 30 分钟超时。

**中期方案**：每次 `_update_status` / `_update_progress` 后写 `./uploads/{task_id}.state.json`，进程启动时扫描目录把非终态任务标为 failed（进程重启后线程必然已死）。这样 GET 返回的是带原因的 failed 而不是 404，不用引入 Redis。要同步更新 `file-lifecycle.md` 的文件清单和清理时机。

---

## 6. uploads 里的孤儿文件永远回收不到

**优先级**：低。

`cleanupUploadsIfOversized` 是遍历数据库任务再删对应文件的，所以没有 DB 记录的文件它永远扫不到。

**证据**：本地 `uploads/9c9fee5a-c8e7-4436-973c-6504da2f3ff7.esm`（31KB，3 月 6 日），库里查不到该 task_id。

**建议**：清理时改成同时扫目录，对文件名里的 taskId 在库中不存在、且修改时间超过 N 天的文件直接删。

---

## 7. steering 文档里的默认模型与代码不一致

**优先级**：低，但会误导排查。

`.kiro/steering/local-dev.md` 写「默认模型 deepseek-reasoner」，代码默认是 `deepseek-v4-flash`（`llm_client._get_model`）。本地启动不设 `LLM_MODEL` 时实际走的是后者。

**建议**：改文档对齐代码，或在启动命令里显式带上 `LLM_MODEL`。
