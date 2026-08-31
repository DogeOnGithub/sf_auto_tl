package com.starfield.api.controller;

import com.starfield.api.dto.LlmPoolDailyUsage;
import com.starfield.api.dto.LlmPoolMemberRequest;
import com.starfield.api.dto.LlmPoolMemberResponse;
import com.starfield.api.dto.LlmPoolTestResponse;
import com.starfield.api.service.LlmPoolService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 默认 LLM 凭证池管理控制器，供星裔（管理员）页面使用
 *
 * <p>所有响应中的 API Key 都是脱敏值。明文只由 LlmPoolInternalController 提供给引擎。
 */
@Slf4j
@RestController
@RequestMapping("/api/llm-pool/members")
@RequiredArgsConstructor
public class LlmPoolController {

    final LlmPoolService llmPoolService;

    /** 每日用量默认回溯天数 */
    private static final int DEFAULT_USAGE_DAYS = 14;

    /**
     * 查询全部成员，附带用量统计与引擎侧实时健康状态
     *
     * @return 成员列表
     */
    @GetMapping
    public ResponseEntity<List<LlmPoolMemberResponse>> listMembers() {
        log.info("[listMembers] 收到查询池成员请求");
        return ResponseEntity.ok(llmPoolService.listMembers());
    }

    /**
     * 新增成员
     *
     * @param request 成员配置
     * @return 新增后的成员
     */
    @PostMapping
    public ResponseEntity<LlmPoolMemberResponse> createMember(@RequestBody LlmPoolMemberRequest request) {
        log.info("[createMember] 收到新增池成员请求 name {}", request.name());
        return ResponseEntity.ok(llmPoolService.createMember(request));
    }

    /**
     * 修改成员，apiKey 留空表示沿用原值
     *
     * @param id      成员 ID
     * @param request 成员配置
     * @return 修改后的成员
     */
    @PutMapping("/{id}")
    public ResponseEntity<LlmPoolMemberResponse> updateMember(@PathVariable Long id,
                                                             @RequestBody LlmPoolMemberRequest request) {
        log.info("[updateMember] 收到修改池成员请求 id {}", id);
        return ResponseEntity.ok(llmPoolService.updateMember(id, request));
    }

    /**
     * 删除成员
     *
     * @param id 成员 ID
     * @return 204 No Content
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteMember(@PathVariable Long id) {
        log.info("[deleteMember] 收到删除池成员请求 id {}", id);
        llmPoolService.deleteMember(id);
        return ResponseEntity.noContent().build();
    }

    /**
     * 验证成员凭证连通性
     *
     * @param id 成员 ID
     * @return 验证结果
     */
    @PostMapping("/{id}/test")
    public ResponseEntity<LlmPoolTestResponse> testMember(@PathVariable Long id) {
        log.info("[testMember] 收到验证池成员请求 id {}", id);
        return ResponseEntity.ok(llmPoolService.testMember(id));
    }

    /**
     * 查询成员每日用量
     *
     * @param id   成员 ID
     * @param days 回溯天数，默认 14
     * @return 按日期升序的每日用量
     */
    @GetMapping("/{id}/usage")
    public ResponseEntity<List<LlmPoolDailyUsage>> getUsage(
            @PathVariable Long id,
            @RequestParam(required = false) Integer days) {
        var resolvedDays = (days == null || days <= 0) ? DEFAULT_USAGE_DAYS : days;
        log.info("[getUsage] 收到查询成员用量请求 id {} days {}", id, resolvedDays);
        return ResponseEntity.ok(llmPoolService.getDailyUsage(id, resolvedDays));
    }
}
