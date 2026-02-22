package com.starfield.api.dto;

import java.util.List;

/**
 * 批量替换译文请求
 *
 * @param ids        指定的记录 ID 列表（为空时替换全任务范围）
 * @param searchStr  要查找的文本
 * @param replaceStr 替换为的文本
 */
public record ConfirmationBatchReplaceRequest(
        List<Long> ids,
        String searchStr,
        String replaceStr
) {}
