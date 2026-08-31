package com.starfield.api.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * 启动时把环境变量里的兜底 LLM 凭证种子导入凭证池
 *
 * <p>池化之前默认额度靠 LLM_API_KEY 等环境变量提供，改造后凭证来源换成了库表。
 * 如果不做这一步，改造上线瞬间池是空的，所有走默认额度的任务会立刻全部失败，
 * 直到管理员手工在页面补上第一条配置。
 *
 * <p>只在池为空时执行，因此重启幂等；导入完成后这三个环境变量就可以从部署配置里下线。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LlmPoolSeedRunner implements ApplicationRunner {

    final LlmPoolService llmPoolService;

    @Value("${llm.pool.seed.base-url:}")
    private String seedBaseUrl;

    @Value("${llm.pool.seed.api-key:}")
    private String seedApiKey;

    @Value("${llm.pool.seed.model:}")
    private String seedModel;

    /**
     * 池为空且环境变量有 Key 时导入一条成员
     *
     * <p>异常只记录不外抛：种子导入失败不该拖住整个应用启动，管理员仍可在页面手工补配置。
     */
    @Override
    public void run(ApplicationArguments args) {
        try {
            var seeded = llmPoolService.seedFromEnv(seedBaseUrl, seedApiKey, seedModel);
            if (seeded) {
                log.info("[run] 已从环境变量种子导入默认凭证池成员");
            }
        } catch (Exception e) {
            log.error("[run] 种子导入默认凭证池失败 不影响启动", e);
        }
    }
}
