# 文件生命周期

## 本地文件

翻译任务产生的本地文件存储在 `starfield-api/uploads/` 目录下，以 `taskId` 命名。

| 文件 | 路径 | 产生时机 |
|------|------|---------|
| 原始文件 | `./uploads/{taskId}.esm` | 用户上传时 |
| 翻译文件 | `./uploads/{taskId}_translated.esm` | Engine 翻译完成后（仅 direct 模式） |
| 备份文件 | `./uploads/{taskId}_backup.esm` | Engine 翻译完成后（仅 direct 模式） |
| ZIP 包 | `./uploads/{taskId}.zip` | 上传 COS 前打包（仅含 translated） |

## 清理时机

### 任务完成（completed）
`handleTaskCompleted` 中：打包 translated 为 zip（以原始文件名存储） → 上传 COS → 删除本地所有文件（原始、translated、backup、zip）

### 任务失败（failed）
`handleTaskFailed` 中：立即删除本地所有文件

### 手动清理（expire）
`expireTask` 中：删除 COS 文件 + 本地文件（兜底） + 确认记录 → 标记任务为 expired

### 定时清理
`cleanupExpiredTasks`（每天凌晨 3 点）：自动清理创建超过 5 天、未关联 creation、已完成的任务

## COS 存储

COS 上存储的是 `{taskId}.zip`（或关联 creation 时以作品名命名），仅包含翻译后的 ESM 文件（以原始文件名存储）。backup 文件不包含在 zip 中。用户下载时通过 `downloadUrl` 访问。

## 数据库字段

`translation_task` 表中记录文件路径：
- `file_path`：原始文件路径（相对路径 `./uploads/{taskId}.esm`）
- `output_file_path`：翻译文件路径（绝对路径）
- `original_backup_path`：备份文件路径（绝对路径）
- `download_url`：COS 下载地址（清理时置 null）

## Strings 模式（开启本地化的 mod）

开启本地化（Localized）的 mod 文本存储在外部 Strings 文件中，用户上传包含三个 Strings 文件的文件夹（前端打包为 zip 后上传），任务 `source_type` 为 `strings`。

### 本地文件

| 文件 | 路径 | 产生时机 |
|------|------|---------|
| 原始目录 | `./uploads/{taskId}/`（含 .strings/.dlstrings/.ilstrings 三个文件） | 用户上传、解压校验后 |
| 翻译目录 | `./uploads/{taskId}_translated/`（三个同名文件） | Engine 翻译完成后（仅 direct 模式） |
| ZIP 包 | `./uploads/{taskId}.zip`（内含小写 `strings/` 子目录） | 上传 COS 前打包 |

strings 模式无 backup 文件。`file_path` 与 `output_file_path` 均为目录，清理时递归删除整个目录。

### 打包结构

ZIP 根目录下为小写 `strings/` 子目录，包含三个翻译后的 Strings 文件（文件名与上传一致，保留 `_zhhans` 后缀）。用户解压到游戏 Data 目录即生效（目录名必须小写）。

### 数据库字段

`translation_task.source_type`：来源类型，`esm`（默认）或 `strings`。strings 模式下 `file_path`/`output_file_path` 为目录，`original_backup_path` 为空。
