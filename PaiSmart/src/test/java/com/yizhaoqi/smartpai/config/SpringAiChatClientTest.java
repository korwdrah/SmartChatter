package com.yizhaoqi.smartpai.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.indices.ElasticsearchIndicesClient;
import co.elastic.clients.elasticsearch.indices.ExistsRequest;
import co.elastic.clients.transport.endpoints.BooleanResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 集成测试：验证 routerChatClient 和 plannerChatClient 能正确调用 GLM API 并返回预期结果。
 * 需要网络可访问 GLM API（https://open.bigmodel.cn/api/paas/v4）。
 * 需要 MySQL、Redis 等中间件可访问（Spring 上下文启动需要）。
 */
@SpringBootTest(properties = "spring.main.allow-bean-definition-overriding=true")
class SpringAiChatClientTest {

    @TestConfiguration
    static class MockEsConfig {
        @Bean
        @Primary
        ElasticsearchClient elasticsearchClient() throws Exception {
            ElasticsearchClient client = mock(ElasticsearchClient.class);
            ElasticsearchIndicesClient indices = mock(ElasticsearchIndicesClient.class);
            BooleanResponse response = mock(BooleanResponse.class);
            when(response.value()).thenReturn(true);
            when(indices.exists(any(ExistsRequest.class))).thenReturn(response);
            when(client.indices()).thenReturn(indices);
            return client;
        }
    }

    @org.springframework.beans.factory.annotation.Autowired
    @Qualifier("routerChatClient")
    private ChatClient routerChatClient;

    @org.springframework.beans.factory.annotation.Autowired
    @Qualifier("plannerChatClient")
    private ChatClient plannerChatClient;

    // ==================== Router 测试 ====================

    @Test
    @DisplayName("路由器 - 告别语应分类为 direct")
    void router_shouldClassifyFarewellAsDirect() {
        String result = routerChatClient.prompt()
                .user("辛苦了，先这样吧")
                .call()
                .content();
        System.out.println("[Router] \"辛苦了，先这样吧\" → " + result);
        assertThat(result).containsIgnoringCase("direct");
    }

    @Test
    @DisplayName("路由器 - 闲聊应分类为 direct")
    void router_shouldClassifyChitchatAsDirect() {
        String result = routerChatClient.prompt()
                .user("今天天气不错")
                .call()
                .content();
        System.out.println("[Router] \"今天天气不错\" → " + result);
        assertThat(result).containsIgnoringCase("direct");
    }

    @Test
    @DisplayName("路由器 - 知识库文档问题应分类为 rag")
    void router_shouldClassifyDocQuestionAsRag() {
        String result = routerChatClient.prompt()
                .user("项目的技术选型用了哪些框架")
                .call()
                .content();
        System.out.println("[Router] \"项目的技术选型用了哪些框架\" → " + result);
        assertThat(result).containsIgnoringCase("rag");
    }

    @Test
    @DisplayName("路由器 - 指代性问题应分类为 rag")
    void router_shouldClassifyReferentialQuestionAsRag() {
        String result = routerChatClient.prompt()
                .user("刚才那个方案里提到的风险点有哪些")
                .call()
                .content();
        System.out.println("[Router] \"刚才那个方案里提到的风险点有哪些\" → " + result);
        assertThat(result).containsIgnoringCase("rag");
    }

    @Test
    @DisplayName("路由器 - 多维度问题应分类为 agent")
    void router_shouldClassifyMultiDimensionQuestionAsAgent() {
        String result = routerChatClient.prompt()
                .user("报销流程是怎样的，审批周期一般多长")
                .call()
                .content();
        System.out.println("[Router] \"报销流程是怎样的，审批周期一般多长\" → " + result);
        assertThat(result).containsIgnoringCase("agent");
    }

    // ==================== Planner 测试 ====================

    @Test
    @DisplayName("规划器 - 单一问题应返回单元素 JSON 数组")
    void planner_shouldReturnSingleQueryForSimpleQuestion() {
        String result = plannerChatClient.prompt()
                .user("React 和 Vue 在性能上有什么区别")
                .call()
                .content();
        System.out.println("[Planner] \"React 和 Vue 在性能上有什么区别\" → " + result);
        assertThat(result).contains("[");
    }

    @Test
    @DisplayName("规划器 - 多维度问题应拆分为多个子查询")
    void planner_shouldDecomposeMultiDimensionQuestion() {
        String result = plannerChatClient.prompt()
                .user("报销流程是怎样的，审批周期一般多长")
                .call()
                .content();
        System.out.println("[Planner] \"报销流程是怎样的，审批周期一般多长\" → " + result);
        assertThat(result).contains("[");
        assertThat(result).containsAnyOf("报销", "审批");
    }

    @Test
    @DisplayName("规划器 - 三方面问题应拆分为多个子查询")
    void planner_shouldDecomposeThreeWayQuestion() {
        String result = plannerChatClient.prompt()
                .user("公司的入职培训、试用期考核和转正流程分别是什么")
                .call()
                .content();
        System.out.println("[Planner] \"公司的入职培训、试用期考核和转正流程分别是什么\" → " + result);
        assertThat(result).contains("[");
        assertThat(result).containsAnyOf("入职", "试用期", "转正");
    }
}
