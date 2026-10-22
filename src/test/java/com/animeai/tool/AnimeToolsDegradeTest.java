package com.animeai.tool;

import java.util.List;
import java.util.Map;

import com.animeai.config.AiProperties;
import com.animeai.model.AnimeCard;
import com.animeai.rag.KnowledgeHit;
import com.animeai.rag.KnowledgeService;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code search_knowledge} 的**降级**行为测试。
 *
 * <p>为什么这件事必须用断言锁住：检索是**增强功能**，一旦 Qdrant 抖动或 embedding
 * 限流就把整轮对话打成 500，等于给主链路绑了个单点。而这类故障在开发环境很少发生，
 * 靠手工「停掉容器试一下」永远覆盖不全（也容易忘）。
 *
 * <p>测试手法：用匿名子类覆盖 {@link KnowledgeService#retrieve}，
 * 不碰网络、不依赖 Qdrant 与 SiliconFlow，因此可以进 CI。
 */
class AnimeToolsDegradeTest {

    /** 只带检索配置的最小 AiProperties；其余字段本测试用不到。 */
    private static AiProperties props(Boolean retrievalEnabled) {
        return new AiProperties(
                null,
                null,
                null,
                null,
                null,
                new AiProperties.Retrieval("hybrid", retrievalEnabled),
                "token");
    }

    private static ObjectProvider<KnowledgeService> providerOf(KnowledgeService service) {
        return new ObjectProvider<>() {
            @Override
            public KnowledgeService getObject() {
                return service;
            }

            @Override
            public KnowledgeService getIfAvailable() {
                return service;
            }
        };
    }

    @Test
    void 检索抛异常时降级为空结果而不是让对话失败() {
        // 模拟 Qdrant 挂掉：retrieve 直接抛异常
        KnowledgeService broken = new KnowledgeService(null, null, props(true)) {
            @Override
            public List<KnowledgeHit> retrieve(String query, int topK, Map<String, Object> filters) {
                throw new IllegalStateException("qdrant down");
            }
        };
        AnimeTools tools = new AnimeTools(null, providerOf(broken), props(true));

        List<AnimeCard> result = tools.searchKnowledge("时间循环 悬疑", 5);

        assertThat(result).as("检索失败时必须返回空结果，让对话继续").isEmpty();
    }

    @Test
    void kill_switch关闭时不调用检索也不花钱() {
        // 如果 kill switch 没生效，这里会因为 retrieve 被调用而抛异常
        KnowledgeService shouldNotBeCalled = new KnowledgeService(null, null, props(false)) {
            @Override
            public List<KnowledgeHit> retrieve(String query, int topK, Map<String, Object> filters) {
                throw new AssertionError("ai.retrieval.enabled=false 时不该调用 retrieve");
            }
        };
        AnimeTools tools = new AnimeTools(null, providerOf(shouldNotBeCalled), props(false));

        assertThat(tools.searchKnowledge("任意查询", 5)).isEmpty();
    }

    @Test
    void 未配置knowledge时返回空而不是抛空指针() {
        AnimeTools tools = new AnimeTools(null, providerOf(null), props(true));

        assertThat(tools.searchKnowledge("任意查询", 5)).isEmpty();
    }

    @Test
    void 正常路径把命中转成卡片() {
        KnowledgeService working = new KnowledgeService(null, null, props(true)) {
            @Override
            public List<KnowledgeHit> retrieve(String query, int topK, Map<String, Object> filters) {
                return List.of(new KnowledgeHit(
                        10380L,
                        0.83,
                        "[标题] 命运石之门",
                        Map.of("name_cn", "命运石之门", "name", "STEINS;GATE", "rating", 8.8, "rank", 8)));
            }
        };
        AnimeTools tools = new AnimeTools(null, providerOf(working), props(true));

        List<AnimeCard> result = tools.searchKnowledge("时间循环 悬疑", 5);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).id()).isEqualTo(10380L);
        assertThat(result.get(0).nameCn()).isEqualTo("命运石之门");
    }

    @Test
    void 条数被夹在1到20之间() {
        KnowledgeService working = new KnowledgeService(null, null, props(true)) {
            @Override
            public List<KnowledgeHit> retrieve(String query, int topK, Map<String, Object> filters) {
                // 断言实际传入的 topK 已被夹紧
                assertThat(topK).isBetween(1, 20);
                return List.of();
            }
        };
        AnimeTools tools = new AnimeTools(null, providerOf(working), props(true));

        tools.searchKnowledge("查询", 999);
        tools.searchKnowledge("查询", 0);
        tools.searchKnowledge("查询", null);
    }
}
