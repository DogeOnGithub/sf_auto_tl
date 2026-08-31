package com.starfield.api.service;

import com.starfield.api.client.EngineClient;
import com.starfield.api.dto.LlmPoolMemberRequest;
import com.starfield.api.dto.LlmPoolStatReportRequest;
import com.starfield.api.repository.LlmPoolMemberRepository;
import com.starfield.api.repository.LlmPoolMemberStatRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;

/**
 * 凭证池服务测试
 *
 * <p>跑真实数据库（H2 PostgreSQL 兼容模式）而不是 mock Repository：这里最容易出错的部分是
 * 两个 group by 聚合查询——sum(bigint) 在 Postgres 下返回 numeric，JDBC 映射成 BigDecimal，
 * 直接强转 Long 会 ClassCastException。mock 掉 Repository 就完全测不到这个转换。
 */
@SpringBootTest
@ActiveProfiles("test")
class LlmPoolServiceTest {

    @Autowired
    LlmPoolService llmPoolService;

    @Autowired
    LlmPoolMemberRepository llmPoolMemberRepository;

    @Autowired
    LlmPoolMemberStatRepository llmPoolMemberStatRepository;

    /** 引擎不参与本测试：健康状态拿不到时服务应降级为只返回配置与统计 */
    @MockBean
    EngineClient engineClient;

    @BeforeEach
    void setUp() {
        llmPoolMemberStatRepository.delete(null);
        llmPoolMemberRepository.delete(null);
        // 不在这里给 getPoolHealth 打桩：若 setUp 里 thenThrow，用例内再 when(...) 重新打桩时
        // 会先真的调一次 mock 而直接抛出来。默认返回 null 已经等价于「引擎给不出状态」
    }

    /** 新增成员后应能查出来，且 Key 已脱敏 */
    @Test
    void createMember_masksApiKeyInResponse() {
        var created = llmPoolService.createMember(request("main", "sk-1234567890abcdef"));

        assertThat(created.id()).isNotNull();
        assertThat(created.maskedApiKey()).doesNotContain("567890abc");
        assertThat(created.maskedApiKey()).startsWith("sk-123");
        assertThat(created.maskedApiKey()).endsWith("cdef");
    }

    /** base_url 误填端点后缀时应在入库前规整掉 */
    @Test
    void createMember_normalizesBaseUrl() {
        var created = llmPoolService.createMember(new LlmPoolMemberRequest(
                "main", "https://api.x.com/v1/chat/completions", "sk-1234567890abcdef",
                "m", 1, true, null));

        assertThat(created.baseUrl()).isEqualTo("https://api.x.com/v1");
    }

    /** 成员名重复应被拒绝：日志和管理页都靠它定位成员 */
    @Test
    void createMember_duplicateName_isRejected() {
        llmPoolService.createMember(request("main", "sk-1234567890abcdef"));

        assertThatThrownBy(() -> llmPoolService.createMember(request("main", "sk-other1234567890")))
                .isInstanceOf(LlmPoolService.DuplicateMemberNameException.class);
    }

    /** 缺 Key 的新增应被拒绝 */
    @Test
    void createMember_withoutApiKey_isRejected() {
        assertThatThrownBy(() -> llmPoolService.createMember(request("main", "  ")))
                .isInstanceOf(LlmPoolService.InvalidPoolMemberException.class);
    }

    /** 修改时 Key 留空表示沿用原值，管理页拿不到明文所以不能强制重填 */
    @Test
    void updateMember_blankApiKey_keepsOriginal() {
        var created = llmPoolService.createMember(request("main", "sk-1234567890abcdef"));

        llmPoolService.updateMember(created.id(), new LlmPoolMemberRequest(
                "renamed", "https://api.y.com/v1", "", "m2", 3, false, "备注"));

        var stored = llmPoolMemberRepository.selectById(created.id());
        assertThat(stored.getApiKey()).isEqualTo("sk-1234567890abcdef");
        assertThat(stored.getName()).isEqualTo("renamed");
        assertThat(stored.getModel()).isEqualTo("m2");
        assertThat(stored.getWeight()).isEqualTo(3);
        assertThat(stored.getEnabled()).isFalse();
    }

    /** 删除成员时用量统计一并清除，否则同名新成员会背着前任的用量被长期跳过 */
    @Test
    void deleteMember_alsoRemovesStats() {
        var created = llmPoolService.createMember(request("main", "sk-1234567890abcdef"));
        llmPoolService.reportStats(report(created.id(), 5, 1, 100, 200, 50));
        assertThat(llmPoolMemberStatRepository.selectCount(null)).isEqualTo(1);

        llmPoolService.deleteMember(created.id());

        assertThat(llmPoolMemberStatRepository.selectCount(null)).isZero();
    }

    /** 上报的增量应累加而不是覆盖：引擎每 100 次请求 flush 一次 */
    @Test
    void reportStats_accumulatesInsteadOfOverwriting() {
        var created = llmPoolService.createMember(request("main", "sk-1234567890abcdef"));

        llmPoolService.reportStats(report(created.id(), 3, 1, 100, 200, 10));
        llmPoolService.reportStats(report(created.id(), 2, 0, 50, 60, 5));

        var members = llmPoolService.listMembers();
        var stat = members.get(0).stat();
        assertThat(stat.windowRequests()).isEqualTo(5);
        assertThat(stat.windowFailures()).isEqualTo(1);
        // 窗口 token 是三类之和：(100+200+10) + (50+60+5)
        assertThat(stat.windowTokens()).isEqualTo(425);
        assertThat(stat.totalRequests()).isEqualTo(5);
        assertThat(stat.totalTokens()).isEqualTo(425);
    }

    /** 有成功请求时刷新成功时间，有失败时记录失败原因 */
    @Test
    void reportStats_recordsLastMarks() {
        var created = llmPoolService.createMember(request("main", "sk-1234567890abcdef"));

        llmPoolService.reportStats(new LlmPoolStatReportRequest(List.of(
                new LlmPoolStatReportRequest.Item(created.id(), 3L, 1L, 1L, 1L, 0L, "rate_limit 429"))));

        var stat = llmPoolService.listMembers().get(0).stat();
        assertThat(stat.lastSuccessAt()).isNotNull();
        assertThat(stat.lastFailureAt()).isNotNull();
        assertThat(stat.lastFailureReason()).isEqualTo("rate_limit 429");
    }

    /** 已删除成员的上报应被跳过：引擎配置缓存有 TTL，删完之后还可能收到收尾上报 */
    @Test
    void reportStats_ignoresUnknownMember() {
        llmPoolService.reportStats(report(999999L, 1, 0, 1, 1, 0));

        assertThat(llmPoolMemberStatRepository.selectCount(null)).isZero();
    }

    /** 引擎给不出健康状态时只降级 runtime 字段，配置和统计照常返回 */
    @Test
    void listMembers_engineDown_stillReturnsConfigAndStats() {
        doThrow(new RuntimeException("engine down")).when(engineClient).getPoolHealth();
        var created = llmPoolService.createMember(request("main", "sk-1234567890abcdef"));
        llmPoolService.reportStats(report(created.id(), 1, 0, 10, 20, 0));

        var members = llmPoolService.listMembers();

        assertThat(members).hasSize(1);
        assertThat(members.get(0).runtime()).isNull();
        assertThat(members.get(0).stat().windowTokens()).isEqualTo(30);
    }

    /** 引擎可达时应把实时健康状态合并进响应 */
    @Test
    void listMembers_mergesEngineRuntime() {
        var created = llmPoolService.createMember(request("main", "sk-1234567890abcdef"));
        when(engineClient.getPoolHealth()).thenReturn(new EngineClient.EnginePoolHealthResponse(
                List.of(new EngineClient.EnginePoolMemberHealth(
                        created.id(), false, 42L, "rate_limit", "slow down"))));

        var runtime = llmPoolService.listMembers().get(0).runtime();

        assertThat(runtime).isNotNull();
        assertThat(runtime.available()).isFalse();
        assertThat(runtime.cooldownRemainingSeconds()).isEqualTo(42);
        assertThat(runtime.lastErrorKind()).isEqualTo("rate_limit");
    }

    /** 引擎拉取成员时要带上窗口用量基线，否则引擎重启后会把流量压到同一个成员上 */
    @Test
    void listInternalMembers_carriesWindowTokensAndPlainKey() {
        var created = llmPoolService.createMember(request("main", "sk-1234567890abcdef"));
        llmPoolService.reportStats(report(created.id(), 1, 0, 100, 200, 300));

        var internal = llmPoolService.listInternalMembers();

        assertThat(internal).hasSize(1);
        assertThat(internal.get(0).apiKey()).isEqualTo("sk-1234567890abcdef");
        assertThat(internal.get(0).windowTokens()).isEqualTo(600);
    }

    /** 停用的成员不该被引擎拉到 */
    @Test
    void listInternalMembers_excludesDisabled() {
        llmPoolService.createMember(new LlmPoolMemberRequest(
                "off", "https://api.x.com/v1", "sk-1234567890abcdef", "m", 1, false, null));

        assertThat(llmPoolService.listInternalMembers()).isEmpty();
    }

    /** 只要有一个启用成员就算默认额度可用 */
    @Test
    void hasEnabledMember_reflectsEnabledFlag() {
        assertThat(llmPoolService.hasEnabledMember()).isFalse();

        var created = llmPoolService.createMember(request("main", "sk-1234567890abcdef"));
        assertThat(llmPoolService.hasEnabledMember()).isTrue();

        llmPoolService.updateMember(created.id(), new LlmPoolMemberRequest(
                "main", "https://api.x.com/v1", null, "m", 1, false, null));
        assertThat(llmPoolService.hasEnabledMember()).isFalse();
    }

    /** 窗口之外的统计不计入窗口口径，但仍计入累计 */
    @Test
    void statSummary_excludesRowsOutsideWindow() {
        var created = llmPoolService.createMember(request("main", "sk-1234567890abcdef"));
        llmPoolService.reportStats(report(created.id(), 1, 0, 10, 10, 0));
        // 把窗口收窄到 1 天，再插一条昨天的记录，它应只进累计不进窗口
        ReflectionTestUtils.setField(llmPoolService, "usageWindowDays", 1);
        var yesterday = llmPoolMemberStatRepository.selectList(null).get(0);
        var extra = new com.starfield.api.entity.LlmPoolMemberStat();
        extra.setMemberId(created.id());
        extra.setStatDate(yesterday.getStatDate().minusDays(3));
        extra.setRequests(7L);
        extra.setFailures(0L);
        extra.setPromptTokens(1000L);
        extra.setCompletionTokens(0L);
        extra.setReasoningTokens(0L);
        llmPoolMemberStatRepository.insert(extra);

        var stat = llmPoolService.listMembers().get(0).stat();

        assertThat(stat.windowDays()).isEqualTo(1);
        assertThat(stat.windowRequests()).isEqualTo(1);
        assertThat(stat.windowTokens()).isEqualTo(20);
        assertThat(stat.totalRequests()).isEqualTo(8);
        assertThat(stat.totalTokens()).isEqualTo(1020);
    }

    /** 种子导入只在池为空时执行一次，重启幂等 */
    @Test
    void seedFromEnv_onlyRunsWhenPoolIsEmpty() {
        assertThat(llmPoolService.seedFromEnv("https://api.x.com/v1", "sk-seed1234567890", "m")).isTrue();
        assertThat(llmPoolMemberRepository.selectCount(null)).isEqualTo(1);

        assertThat(llmPoolService.seedFromEnv("https://api.x.com/v1", "sk-seed1234567890", "m")).isFalse();
        assertThat(llmPoolMemberRepository.selectCount(null)).isEqualTo(1);
    }

    /** 环境变量没配 Key 时不导入 */
    @Test
    void seedFromEnv_withoutKey_doesNothing() {
        assertThat(llmPoolService.seedFromEnv("https://api.x.com/v1", "", "m")).isFalse();
        assertThat(llmPoolMemberRepository.selectCount(null)).isZero();
    }

    /** 验证连通性走引擎，凭证不可用时收敛成 success=false 而不是抛异常 */
    @Test
    void testMember_delegatesToEngine() {
        var created = llmPoolService.createMember(request("main", "sk-1234567890abcdef"));
        when(engineClient.testPoolMember(any())).thenReturn(
                new EngineClient.EnginePoolTestResponse(false, "auth invalid api key", 120L));

        var result = llmPoolService.testMember(created.id());

        assertThat(result.success()).isFalse();
        assertThat(result.message()).contains("invalid api key");
        assertThat(result.latencyMs()).isEqualTo(120);
    }

    /** 不存在的成员操作应抛 MemberNotFoundException */
    @Test
    void operationsOnMissingMember_throwNotFound() {
        assertThatThrownBy(() -> llmPoolService.deleteMember(999999L))
                .isInstanceOf(LlmPoolService.MemberNotFoundException.class);
        assertThatThrownBy(() -> llmPoolService.getDailyUsage(999999L, 7))
                .isInstanceOf(LlmPoolService.MemberNotFoundException.class);
    }

    /** 每日用量按日期升序返回 */
    @Test
    void getDailyUsage_returnsRowsForMember() {
        var created = llmPoolService.createMember(request("main", "sk-1234567890abcdef"));
        llmPoolService.reportStats(report(created.id(), 2, 1, 10, 20, 30));

        var usage = llmPoolService.getDailyUsage(created.id(), 14);

        assertThat(usage).hasSize(1);
        assertThat(usage.get(0).requests()).isEqualTo(2);
        assertThat(usage.get(0).failures()).isEqualTo(1);
        assertThat(usage.get(0).promptTokens()).isEqualTo(10);
    }

    /** 构造一个成员新增请求 */
    private LlmPoolMemberRequest request(String name, String apiKey) {
        return new LlmPoolMemberRequest(name, "https://api.x.com/v1", apiKey, "m", 1, true, null);
    }

    /** 构造一次用量上报 */
    private LlmPoolStatReportRequest report(Long memberId, long requests, long failures,
                                            long promptTokens, long completionTokens, long reasoningTokens) {
        return new LlmPoolStatReportRequest(List.of(new LlmPoolStatReportRequest.Item(
                memberId, requests, failures, promptTokens, completionTokens, reasoningTokens, null)));
    }
}
