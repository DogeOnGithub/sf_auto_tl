package com.starfield.api.controller;

import com.starfield.api.dto.LlmPoolInternalMember;
import com.starfield.api.dto.LlmPoolStatReportRequest;
import com.starfield.api.service.LlmPoolService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 引擎专用的凭证池接口
 *
 * <p>和 LlmPoolController 物理分开而不是加个参数开关：这里返回明文 API Key，
 * 分成两个类之后「哪个接口会吐明文凭证」在文件层面就是显式的，不会因为某次改动
 * 顺手把明文字段带进面向浏览器的响应里。
 *
 * <p>该路径只应在服务间网络可达，不要通过前端反向代理暴露出去。
 */
@Slf4j
@RestController
@RequestMapping("/api/internal/llm-pool")
@RequiredArgsConstructor
public class LlmPoolInternalController {

    final LlmPoolService llmPoolService;

    /**
     * 返回启用中的池成员，含明文 API Key
     *
     * @return 启用成员列表
     */
    @GetMapping("/members")
    public ResponseEntity<List<LlmPoolInternalMember>> listMembers() {
        log.debug("[listMembers] 引擎请求拉取池成员");
        return ResponseEntity.ok(llmPoolService.listInternalMembers());
    }

    /**
     * 接收引擎上报的用量增量
     *
     * @param request 各成员的用量增量
     * @return 204 No Content
     */
    @PostMapping("/stats")
    public ResponseEntity<Void> reportStats(@RequestBody LlmPoolStatReportRequest request) {
        log.debug("[reportStats] 引擎上报用量增量");
        llmPoolService.reportStats(request);
        return ResponseEntity.noContent().build();
    }
}
