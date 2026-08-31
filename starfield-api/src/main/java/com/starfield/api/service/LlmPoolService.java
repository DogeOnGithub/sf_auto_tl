package com.starfield.api.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.starfield.api.client.EngineClient;
import com.starfield.api.dto.LlmPoolDailyUsage;
import com.starfield.api.dto.LlmPoolInternalMember;
import com.starfield.api.dto.LlmPoolMemberRequest;
import com.starfield.api.dto.LlmPoolMemberResponse;
import com.starfield.api.dto.LlmPoolMemberRuntime;
import com.starfield.api.dto.LlmPoolMemberStatSummary;
import com.starfield.api.dto.LlmPoolStatReportRequest;
import com.starfield.api.dto.LlmPoolTestResponse;
import com.starfield.api.entity.LlmPoolMember;
import com.starfield.api.entity.LlmPoolMemberStat;
import com.starfield.api.repository.LlmPoolMemberRepository;
import com.starfield.api.repository.LlmPoolMemberStatRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * 默认 LLM 凭证池服务，负责成员配置的增删改查、用量统计聚合与连通性验证
 *
 * <p>池只服务「走默认额度」的任务。用户自带 KEY 时凭证从上传请求直达引擎，不经过这里，
 * 也不计入统计——那是用户自己的钱，混进来会污染成本分散度的判断。
 *
 * <p>配置（成员、weight、启用状态）持久化在库里，运行时健康（冷却）只存在于引擎内存中。
 * 管理页看到的是两者合并后的视图。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LlmPoolService {

    final LlmPoolMemberRepository llmPoolMemberRepository;
    final LlmPoolMemberStatRepository llmPoolMemberStatRepository;
    final EngineClient engineClient;

    /** 用量滚动窗口天数，必须与引擎调度口径一致，否则管理页看到的分布不是调度实际依据的分布 */
    @Value("${llm.pool.usage-window-days:7}")
    private int usageWindowDays;

    /** Key 脱敏后保留的前缀长度，能看出是哪家供应商的 Key 格式 */
    private static final int MASK_PREFIX_LEN = 6;

    /** Key 脱敏后保留的后缀长度，足够人工核对是不是自己填的那把 */
    private static final int MASK_SUFFIX_LEN = 4;

    /** 短于该长度的 Key 全部打码，保留片段反而等于泄露大半 */
    private static final int MASK_MIN_LEN = 12;

    /** SDK 会自行拼接的端点后缀，出现在 base_url 末尾时属于误填 */
    private static final String CHAT_COMPLETIONS_SUFFIX = "/chat/completions";

    /** 默认成本分摊配比 */
    private static final int DEFAULT_WEIGHT = 1;

    /**
     * 查询全部成员，附带滚动窗口用量统计与引擎侧实时健康状态
     *
     * <p>引擎不可达时只降级掉 runtime 字段而不是整体报错：配置管理本身不依赖引擎，
     * 引擎挂着的时候管理员往往正需要进来看配置。
     *
     * @return 成员列表，按名称排序保证页面顺序稳定
     */
    public List<LlmPoolMemberResponse> listMembers() {
        log.info("[listMembers] 查询池成员列表");
        var members = llmPoolMemberRepository.selectList(
                new LambdaQueryWrapper<LlmPoolMember>().orderByAsc(LlmPoolMember::getName));
        if (members.isEmpty()) {
            log.info("[listMembers] 池为空");
            return List.of();
        }

        var statSummaries = buildStatSummaries();
        var runtimes = fetchRuntimes();

        var responses = members.stream()
                .map(m -> toResponse(m, statSummaries.get(m.getId()), runtimes.get(m.getId())))
                .collect(Collectors.toList());
        log.info("[listMembers] 返回成员数量 {}", responses.size());
        return responses;
    }

    /**
     * 新增成员
     *
     * @param request 成员配置
     * @return 新增后的成员
     * @throws InvalidPoolMemberException 必填项缺失或 weight 非正
     * @throws DuplicateMemberNameException 成员名已存在
     */
    public LlmPoolMemberResponse createMember(LlmPoolMemberRequest request) {
        log.info("[createMember] 新增池成员 name {} model {}", request.name(), request.model());
        validateForCreate(request);
        requireNameAvailable(request.name(), null);

        var member = new LlmPoolMember();
        member.setName(request.name().trim());
        member.setBaseUrl(normalizeBaseUrl(request.baseUrl().trim()));
        member.setApiKey(request.apiKey().trim());
        member.setModel(request.model().trim());
        member.setWeight(resolveWeight(request.weight()));
        member.setEnabled(Objects.isNull(request.enabled()) || request.enabled());
        member.setRemark(request.remark());
        llmPoolMemberRepository.insert(member);

        log.info("[createMember] 池成员新增成功 id {} name {}", member.getId(), member.getName());
        return toResponse(member, null, null);
    }

    /**
     * 修改成员
     *
     * <p>apiKey 留空表示沿用原值：管理页拿不到明文，若要求必填就会逼着管理员为了改 weight
     * 而重新粘贴一次 Key，粘错就静默换成了一把废 Key。
     *
     * @param id      成员 ID
     * @param request 成员配置
     * @return 修改后的成员
     * @throws MemberNotFoundException      成员不存在
     * @throws InvalidPoolMemberException   必填项缺失或 weight 非正
     * @throws DuplicateMemberNameException 成员名与其他成员冲突
     */
    public LlmPoolMemberResponse updateMember(Long id, LlmPoolMemberRequest request) {
        log.info("[updateMember] 修改池成员 id {} name {}", id, request.name());
        var member = requireMember(id);
        validateForUpdate(request);
        requireNameAvailable(request.name(), id);

        member.setName(request.name().trim());
        member.setBaseUrl(normalizeBaseUrl(request.baseUrl().trim()));
        member.setModel(request.model().trim());
        member.setWeight(resolveWeight(request.weight()));
        if (Objects.nonNull(request.enabled())) {
            member.setEnabled(request.enabled());
        }
        member.setRemark(request.remark());
        if (Objects.nonNull(request.apiKey()) && !request.apiKey().isBlank()) {
            member.setApiKey(request.apiKey().trim());
            log.info("[updateMember] 同时更新了 API Key id {}", id);
        }
        llmPoolMemberRepository.updateById(member);

        log.info("[updateMember] 池成员修改成功 id {}", id);
        var summaries = buildStatSummaries();
        var runtimes = fetchRuntimes();
        return toResponse(member, summaries.get(id), runtimes.get(id));
    }

    /**
     * 删除成员，同时清掉它的用量统计
     *
     * <p>统计一并删除而不是保留孤儿行：成员名可复用，留着旧统计会让新成员一上线就背着
     * 前任的用量，直接被调度判定为「已经用得很多」而长期不被选中。
     *
     * @param id 成员 ID
     * @throws MemberNotFoundException 成员不存在
     */
    public void deleteMember(Long id) {
        log.info("[deleteMember] 删除池成员 id {}", id);
        requireMember(id);
        llmPoolMemberStatRepository.delete(
                new LambdaQueryWrapper<LlmPoolMemberStat>().eq(LlmPoolMemberStat::getMemberId, id));
        llmPoolMemberRepository.deleteById(id);
        log.info("[deleteMember] 池成员删除成功 id {}", id);
    }

    /**
     * 验证成员凭证是否可用，由引擎打一次极小的补全请求
     *
     * @param id 成员 ID
     * @return 验证结果
     * @throws MemberNotFoundException 成员不存在
     */
    public LlmPoolTestResponse testMember(Long id) {
        log.info("[testMember] 验证池成员连通性 id {}", id);
        var member = requireMember(id);
        var result = engineClient.testPoolMember(new EngineClient.EnginePoolTestRequest(
                member.getBaseUrl(), member.getApiKey(), member.getModel()));
        var success = Boolean.TRUE.equals(result.success());
        log.info("[testMember] 验证完成 id {} success {} latencyMs {}", id, success, result.latencyMs());
        return new LlmPoolTestResponse(
                success,
                result.message(),
                Objects.isNull(result.latencyMs()) ? 0L : result.latencyMs());
    }

    /**
     * 查询成员最近若干天的每日用量，管理页展开看成本分布用
     *
     * @param id   成员 ID
     * @param days 天数
     * @return 按日期升序的每日用量，无记录的日期不补零
     * @throws MemberNotFoundException 成员不存在
     */
    public List<LlmPoolDailyUsage> getDailyUsage(Long id, int days) {
        log.info("[getDailyUsage] 查询成员每日用量 id {} days {}", id, days);
        requireMember(id);
        var since = LocalDate.now().minusDays(Math.max(days, 1) - 1L);
        var rows = llmPoolMemberStatRepository.selectList(new LambdaQueryWrapper<LlmPoolMemberStat>()
                .eq(LlmPoolMemberStat::getMemberId, id)
                .ge(LlmPoolMemberStat::getStatDate, since)
                .orderByAsc(LlmPoolMemberStat::getStatDate));
        return rows.stream()
                .map(r -> new LlmPoolDailyUsage(
                        r.getStatDate(),
                        nvl(r.getRequests()),
                        nvl(r.getFailures()),
                        nvl(r.getPromptTokens()),
                        nvl(r.getCompletionTokens()),
                        nvl(r.getReasoningTokens())))
                .collect(Collectors.toList());
    }

    /**
     * 返回启用中的成员（含明文 Key），仅供引擎拉取
     *
     * @return 启用成员列表，池为空时返回空列表
     */
    public List<LlmPoolInternalMember> listInternalMembers() {
        var members = llmPoolMemberRepository.selectList(new LambdaQueryWrapper<LlmPoolMember>()
                .eq(LlmPoolMember::getEnabled, true)
                .orderByAsc(LlmPoolMember::getId));
        if (members.isEmpty()) {
            log.info("[listInternalMembers] 引擎拉取启用成员 池为空");
            return List.of();
        }
        var windowTokens = loadWindowTokens();
        log.info("[listInternalMembers] 引擎拉取启用成员 count {}", members.size());
        return members.stream()
                .map(m -> new LlmPoolInternalMember(
                        m.getId(),
                        m.getName(),
                        m.getBaseUrl(),
                        m.getApiKey(),
                        m.getModel(),
                        resolveWeight(m.getWeight()),
                        windowTokens.getOrDefault(m.getId(), 0L)))
                .collect(Collectors.toList());
    }

    /**
     * 查询各成员在滚动窗口内已消耗的 token 数
     *
     * <p>给引擎当调度基线用。引擎自己只知道本进程启动以来的用量，重启后归零，
     * 不喂这个基线会让重启后的流量集中打到排序靠前的成员上。
     *
     * @return memberId -> 窗口内 token 总量
     */
    private Map<Long, Long> loadWindowTokens() {
        var windowStart = LocalDate.now().minusDays(Math.max(usageWindowDays, 1) - 1L);
        var wrapper = new QueryWrapper<LlmPoolMemberStat>()
                .select("member_id",
                        "sum(prompt_tokens + completion_tokens + reasoning_tokens) as window_tokens")
                .ge("stat_date", windowStart)
                .groupBy("member_id");
        var rows = llmPoolMemberStatRepository.selectMaps(wrapper);
        var result = new HashMap<Long, Long>();
        for (var row : rows) {
            var memberId = aggValue(row, "member_id");
            if (memberId == 0) {
                continue;
            }
            result.put(memberId, aggValue(row, "window_tokens"));
        }
        return result;
    }

    /**
     * 累加引擎上报的用量增量到当天的统计行
     *
     * <p>按增量累加而非覆盖快照：引擎每 100 批 flush 一次，只在任务结束上报的话，
     * 跑几小时的任务中途引擎重启就会丢掉全部用量。
     *
     * <p>已删除的成员上报会被跳过而不是报错：引擎的配置缓存有 TTL，管理员删完成员后
     * 最多一个 TTL 内还会收到该成员的收尾上报，那属于正常时序而不是故障。
     *
     * @param request 各成员的用量增量
     */
    public void reportStats(LlmPoolStatReportRequest request) {
        if (Objects.isNull(request) || Objects.isNull(request.items()) || request.items().isEmpty()) {
            log.info("[reportStats] 上报内容为空 跳过");
            return;
        }
        var today = LocalDate.now();
        for (var item : request.items()) {
            if (Objects.isNull(item.memberId())) {
                log.warn("[reportStats] 上报项缺少 memberId 跳过");
                continue;
            }
            if (Objects.isNull(llmPoolMemberRepository.selectById(item.memberId()))) {
                log.warn("[reportStats] 成员已不存在 跳过上报 memberId {}", item.memberId());
                continue;
            }
            accumulate(item, today);
        }
        log.info("[reportStats] 用量上报累加完成 items {}", request.items().size());
    }

    /**
     * 池中是否还有启用成员
     *
     * <p>只看配置层面：全部成员正在冷却属于引擎侧的瞬时状态，不在这里拦，
     * 否则会把「几分钟后自然恢复」误判成「请自带 KEY」。
     *
     * @return 有启用成员返回 true
     */
    public boolean hasEnabledMember() {
        var count = llmPoolMemberRepository.selectCount(new LambdaQueryWrapper<LlmPoolMember>()
                .eq(LlmPoolMember::getEnabled, true));
        return Objects.nonNull(count) && count > 0;
    }

    /**
     * 从环境变量种子导入首个成员
     *
     * <p>池化上线时库里是空的，而线上原本靠 LLM_API_KEY 环境变量服务默认路径。
     * 不做这步导入，改造一上线所有走默认额度的任务会立刻全部失败，直到管理员手工补一条。
     * 只在池为空且环境变量有值时执行一次，导入后环境变量即可下线。
     *
     * @param baseUrl 环境变量里的接口地址
     * @param apiKey  环境变量里的 API Key
     * @param model   环境变量里的模型名
     * @return 实际导入了返回 true
     */
    public boolean seedFromEnv(String baseUrl, String apiKey, String model) {
        if (Objects.isNull(apiKey) || apiKey.isBlank()) {
            log.info("[seedFromEnv] 环境变量未配置 API Key 跳过种子导入");
            return false;
        }
        var total = llmPoolMemberRepository.selectCount(null);
        if (Objects.nonNull(total) && total > 0) {
            log.info("[seedFromEnv] 池已有成员 跳过种子导入 count {}", total);
            return false;
        }
        if (Objects.isNull(baseUrl) || baseUrl.isBlank() || Objects.isNull(model) || model.isBlank()) {
            log.warn("[seedFromEnv] 环境变量缺少 base_url 或 model 无法种子导入 baseUrl {} model {}", baseUrl, model);
            return false;
        }

        var member = new LlmPoolMember();
        member.setName("env-seed");
        member.setBaseUrl(normalizeBaseUrl(baseUrl.trim()));
        member.setApiKey(apiKey.trim());
        member.setModel(model.trim());
        member.setWeight(DEFAULT_WEIGHT);
        member.setEnabled(true);
        member.setRemark("由环境变量 LLM_API_KEY 自动导入 可在此页面改名或调整配比");
        llmPoolMemberRepository.insert(member);
        log.info("[seedFromEnv] 种子导入成功 id {} baseUrl {} model {}", member.getId(), member.getBaseUrl(), member.getModel());
        return true;
    }

    /**
     * 累加单个成员当天的用量
     *
     * <p>先查后写而不是直接 upsert：MyBatis-Plus 没有跨库的 upsert 抽象，
     * 而唯一索引冲突在这里是可预期的（引擎与定时任务可能同时落同一天），捕获后转为更新。
     */
    private void accumulate(LlmPoolStatReportRequest.Item item, LocalDate today) {
        var existing = findStat(item.memberId(), today);
        if (Objects.isNull(existing)) {
            var stat = new LlmPoolMemberStat();
            stat.setMemberId(item.memberId());
            stat.setStatDate(today);
            stat.setRequests(nvl(item.requests()));
            stat.setFailures(nvl(item.failures()));
            stat.setPromptTokens(nvl(item.promptTokens()));
            stat.setCompletionTokens(nvl(item.completionTokens()));
            stat.setReasoningTokens(nvl(item.reasoningTokens()));
            applyLastMarks(stat, item);
            try {
                llmPoolMemberStatRepository.insert(stat);
                return;
            } catch (DuplicateKeyException e) {
                log.info("[accumulate] 当天统计行已被并发创建 转为更新 memberId {}", item.memberId());
                existing = findStat(item.memberId(), today);
                if (Objects.isNull(existing)) {
                    log.warn("[accumulate] 冲突后仍查不到统计行 放弃本次累加 memberId {}", item.memberId());
                    return;
                }
            }
        }

        existing.setRequests(nvl(existing.getRequests()) + nvl(item.requests()));
        existing.setFailures(nvl(existing.getFailures()) + nvl(item.failures()));
        existing.setPromptTokens(nvl(existing.getPromptTokens()) + nvl(item.promptTokens()));
        existing.setCompletionTokens(nvl(existing.getCompletionTokens()) + nvl(item.completionTokens()));
        existing.setReasoningTokens(nvl(existing.getReasoningTokens()) + nvl(item.reasoningTokens()));
        applyLastMarks(existing, item);
        llmPoolMemberStatRepository.updateById(existing);
    }

    /**
     * 依据本次增量刷新最近成功/失败标记
     *
     * <p>「有成功请求」用 requests 减 failures 推导，引擎不需要额外上报成功数。
     */
    private void applyLastMarks(LlmPoolMemberStat stat, LlmPoolStatReportRequest.Item item) {
        var now = LocalDateTime.now();
        var failures = nvl(item.failures());
        var successes = nvl(item.requests()) - failures;
        if (successes > 0) {
            stat.setLastSuccessAt(now);
        }
        if (failures > 0) {
            stat.setLastFailureAt(now);
            if (Objects.nonNull(item.lastFailureReason()) && !item.lastFailureReason().isBlank()) {
                stat.setLastFailureReason(item.lastFailureReason());
            }
        }
    }

    /**
     * 查询指定成员指定日期的统计行
     */
    private LlmPoolMemberStat findStat(Long memberId, LocalDate statDate) {
        return llmPoolMemberStatRepository.selectOne(new LambdaQueryWrapper<LlmPoolMemberStat>()
                .eq(LlmPoolMemberStat::getMemberId, memberId)
                .eq(LlmPoolMemberStat::getStatDate, statDate));
    }

    /**
     * 聚合各成员的窗口内与累计用量
     *
     * <p>窗口部分直接把窗口内的行捞出来在内存里合并（成员数 × 窗口天数，量级极小），
     * 顺带能拿到最近失败原因；累计部分走一次 group by 聚合，避免把历史全表拉回来。
     *
     * @return memberId -> 统计摘要
     */
    private Map<Long, LlmPoolMemberStatSummary> buildStatSummaries() {
        var windowStart = LocalDate.now().minusDays(Math.max(usageWindowDays, 1) - 1L);
        var windowRows = llmPoolMemberStatRepository.selectList(new LambdaQueryWrapper<LlmPoolMemberStat>()
                .ge(LlmPoolMemberStat::getStatDate, windowStart));
        var windowByMember = windowRows.stream().collect(Collectors.groupingBy(LlmPoolMemberStat::getMemberId));

        var totals = loadLifetimeTotals();

        var result = new HashMap<Long, LlmPoolMemberStatSummary>();
        var memberIds = new ArrayList<Long>(totals.keySet());
        windowByMember.keySet().stream().filter(id -> !totals.containsKey(id)).forEach(memberIds::add);

        for (var memberId : memberIds) {
            var rows = windowByMember.getOrDefault(memberId, List.of());
            long windowRequests = 0;
            long windowFailures = 0;
            long windowTokens = 0;
            for (var row : rows) {
                windowRequests += nvl(row.getRequests());
                windowFailures += nvl(row.getFailures());
                windowTokens += nvl(row.getPromptTokens()) + nvl(row.getCompletionTokens()) + nvl(row.getReasoningTokens());
            }
            var lastSuccessAt = rows.stream()
                    .map(LlmPoolMemberStat::getLastSuccessAt)
                    .filter(Objects::nonNull)
                    .max(Comparator.naturalOrder())
                    .orElse(null);
            var lastFailureRow = rows.stream()
                    .filter(r -> Objects.nonNull(r.getLastFailureAt()))
                    .max(Comparator.comparing(LlmPoolMemberStat::getLastFailureAt))
                    .orElse(null);

            var total = totals.getOrDefault(memberId, new long[]{0, 0, 0});
            result.put(memberId, new LlmPoolMemberStatSummary(
                    Math.max(usageWindowDays, 1),
                    windowRequests,
                    windowFailures,
                    windowTokens,
                    total[0],
                    total[1],
                    total[2],
                    lastSuccessAt,
                    Objects.isNull(lastFailureRow) ? null : lastFailureRow.getLastFailureAt(),
                    Objects.isNull(lastFailureRow) ? null : lastFailureRow.getLastFailureReason()));
        }
        return result;
    }

    /**
     * 一次 group by 查出各成员的累计请求数、失败数与 token 总量
     *
     * @return memberId -> [requests, failures, tokens]
     */
    private Map<Long, long[]> loadLifetimeTotals() {
        var wrapper = new QueryWrapper<LlmPoolMemberStat>()
                .select("member_id",
                        "sum(requests) as total_requests",
                        "sum(failures) as total_failures",
                        "sum(prompt_tokens + completion_tokens + reasoning_tokens) as total_tokens")
                .groupBy("member_id");
        var rows = llmPoolMemberStatRepository.selectMaps(wrapper);
        var result = new HashMap<Long, long[]>();
        for (var row : rows) {
            var memberId = aggValue(row, "member_id");
            if (memberId == 0) {
                continue;
            }
            result.put(memberId, new long[]{
                    aggValue(row, "total_requests"),
                    aggValue(row, "total_failures"),
                    aggValue(row, "total_tokens"),
            });
        }
        return result;
    }

    /**
     * 拉取引擎侧的实时健康状态
     *
     * @return memberId -> 运行时状态，引擎不可达时返回空 Map
     */
    private Map<Long, LlmPoolMemberRuntime> fetchRuntimes() {
        try {
            var health = engineClient.getPoolHealth();
            if (Objects.isNull(health) || Objects.isNull(health.members())) {
                return Map.of();
            }
            var result = new HashMap<Long, LlmPoolMemberRuntime>();
            for (var m : health.members()) {
                if (Objects.isNull(m.memberId())) {
                    continue;
                }
                result.put(m.memberId(), new LlmPoolMemberRuntime(
                        !Boolean.FALSE.equals(m.available()),
                        Objects.isNull(m.cooldownRemainingSeconds()) ? 0L : m.cooldownRemainingSeconds(),
                        m.lastErrorKind(),
                        m.lastErrorMessage()));
            }
            return result;
        } catch (Exception e) {
            log.warn("[fetchRuntimes] 引擎健康状态不可用 降级为仅展示配置与统计 error {}", e.getMessage());
            return Map.of();
        }
    }

    /**
     * 实体转响应 DTO，Key 一律脱敏
     */
    private LlmPoolMemberResponse toResponse(LlmPoolMember member,
                                            LlmPoolMemberStatSummary stat,
                                            LlmPoolMemberRuntime runtime) {
        return new LlmPoolMemberResponse(
                member.getId(),
                member.getName(),
                member.getBaseUrl(),
                maskApiKey(member.getApiKey()),
                member.getModel(),
                resolveWeight(member.getWeight()),
                !Boolean.FALSE.equals(member.getEnabled()),
                member.getRemark(),
                stat,
                runtime,
                member.getCreatedAt(),
                member.getUpdatedAt());
    }

    /**
     * 脱敏 API Key，保留头尾便于人工核对
     */
    private String maskApiKey(String apiKey) {
        if (Objects.isNull(apiKey) || apiKey.isBlank()) {
            return "";
        }
        if (apiKey.length() < MASK_MIN_LEN) {
            return "*".repeat(apiKey.length());
        }
        return apiKey.substring(0, MASK_PREFIX_LEN) + "****" + apiKey.substring(apiKey.length() - MASK_SUFFIX_LEN);
    }

    /**
     * 规整 base_url，去掉误填的 /chat/completions 端点后缀
     *
     * <p>引擎侧也会规整一次，这里同样处理是为了让库里和管理页显示的都是干净值，
     * 否则管理员会一直看着一个错的地址却发现调用是通的，下次照抄这个错值。
     */
    private String normalizeBaseUrl(String baseUrl) {
        var normalized = baseUrl;
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        if (normalized.endsWith(CHAT_COMPLETIONS_SUFFIX)) {
            normalized = normalized.substring(0, normalized.length() - CHAT_COMPLETIONS_SUFFIX.length());
            log.warn("[normalizeBaseUrl] base_url 误填了端点后缀 已自动去掉 原值 {} 修正为 {}", baseUrl, normalized);
        }
        return normalized.isBlank() ? baseUrl : normalized;
    }

    /**
     * weight 缺失或非正时回落默认值
     */
    private int resolveWeight(Integer weight) {
        return (Objects.isNull(weight) || weight <= 0) ? DEFAULT_WEIGHT : weight;
    }

    /**
     * 校验新增入参，Key 必填
     */
    private void validateForCreate(LlmPoolMemberRequest request) {
        validateCommon(request);
        if (Objects.isNull(request.apiKey()) || request.apiKey().isBlank()) {
            log.warn("[validateForCreate] 新增成员缺少 API Key name {}", request.name());
            throw new InvalidPoolMemberException("API Key 不能为空");
        }
    }

    /**
     * 校验修改入参，Key 允许留空表示不改
     */
    private void validateForUpdate(LlmPoolMemberRequest request) {
        validateCommon(request);
    }

    /**
     * 校验名称、地址、模型与 weight
     */
    private void validateCommon(LlmPoolMemberRequest request) {
        if (Objects.isNull(request.name()) || request.name().isBlank()) {
            throw new InvalidPoolMemberException("成员名不能为空");
        }
        if (Objects.isNull(request.baseUrl()) || request.baseUrl().isBlank()) {
            throw new InvalidPoolMemberException("API 地址不能为空");
        }
        if (Objects.isNull(request.model()) || request.model().isBlank()) {
            throw new InvalidPoolMemberException("模型名称不能为空");
        }
        if (Objects.nonNull(request.weight()) && request.weight() <= 0) {
            throw new InvalidPoolMemberException("配比必须为正整数");
        }
    }

    /**
     * 校验成员名未被其他成员占用
     *
     * @param name      待校验名称
     * @param excludeId 修改场景下排除自身，新增时传 null
     */
    private void requireNameAvailable(String name, Long excludeId) {
        var wrapper = new LambdaQueryWrapper<LlmPoolMember>().eq(LlmPoolMember::getName, name.trim());
        if (Objects.nonNull(excludeId)) {
            wrapper.ne(LlmPoolMember::getId, excludeId);
        }
        var count = llmPoolMemberRepository.selectCount(wrapper);
        if (Objects.nonNull(count) && count > 0) {
            log.warn("[requireNameAvailable] 成员名已存在 name {}", name);
            throw new DuplicateMemberNameException(name.trim());
        }
    }

    /**
     * 按 ID 取成员，不存在则抛异常
     */
    private LlmPoolMember requireMember(Long id) {
        var member = llmPoolMemberRepository.selectById(id);
        if (Objects.isNull(member)) {
            log.warn("[requireMember] 池成员不存在 id {}", id);
            throw new MemberNotFoundException(id);
        }
        return member;
    }

    /**
     * null 安全的 long 取值
     */
    private static long nvl(Long value) {
        return Objects.isNull(value) ? 0L : value;
    }

    /**
     * 从 selectMaps 的结果行里取聚合值
     *
     * <p>列标签大小写不能假定：Postgres 驱动回小写，H2 回大写，写死一种拿不到值时会静默变成 0，
     * 表现为管理页上所有累计统计都是 0 却没有任何报错。两种都试一次。
     *
     * @param row    一行聚合结果
     * @param column 建表时的列名或别名（小写）
     * @return 聚合值，取不到时为 0
     */
    private static long aggValue(Map<String, Object> row, String column) {
        var value = row.get(column);
        if (Objects.isNull(value)) {
            value = row.get(column.toUpperCase(Locale.ROOT));
        }
        return toLong(value);
    }

    /**
     * 聚合结果取 long
     *
     * <p>Postgres 的 sum(bigint) 返回 numeric，JDBC 映射成 BigDecimal，直接强转 Long 会 ClassCastException。
     */
    private static long toLong(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        return 0L;
    }

    /**
     * 池成员配置非法异常
     */
    public static class InvalidPoolMemberException extends RuntimeException {
        public InvalidPoolMemberException(String message) {
            super(message);
        }
    }

    /**
     * 池成员名重复异常
     */
    public static class DuplicateMemberNameException extends RuntimeException {
        public DuplicateMemberNameException(String name) {
            super("成员名已存在 name " + name);
        }
    }

    /**
     * 池成员不存在异常
     */
    public static class MemberNotFoundException extends RuntimeException {
        public MemberNotFoundException(Long id) {
            super("池成员不存在 id " + id);
        }
    }

    /**
     * 默认额度不可用异常，池里没有启用成员时抛出
     */
    public static class PoolUnavailableException extends RuntimeException {
        public PoolUnavailableException() {
            super("默认额度当前不可用 请自带 API Key");
        }
    }
}
