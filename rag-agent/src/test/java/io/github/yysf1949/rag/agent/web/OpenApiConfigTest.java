package io.github.yysf1949.rag.agent.web;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.tags.Tag;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * OpenApiConfig 纯单元测试 — JUnit 5，不启动 Spring Context。
 *
 * <p>直接 {@code new OpenApiConfig()} 调用 Bean 方法，验证
 * OpenAPI 元数据（title / version / description）和标签定义。</p>
 */
@DisplayName("OpenApiConfig — 纯单元测试")
class OpenApiConfigTest {

    private final OpenApiConfig config = new OpenApiConfig();

    @Test
    @DisplayName("customerServiceOpenAPI() — Bean 能正确创建，不返回 null")
    void beanCreation_returnsNonNull() {
        OpenAPI openAPI = config.customerServiceOpenAPI();
        assertThat(openAPI).isNotNull();
    }

    @Test
    @DisplayName("OpenAPI Info 包含正确的 title")
    void infoTitle_isCorrect() {
        OpenAPI openAPI = config.customerServiceOpenAPI();
        Info info = openAPI.getInfo();
        assertThat(info).isNotNull();
        assertThat(info.getTitle()).isEqualTo("AI Agent 客服系统 API");
    }

    @Test
    @DisplayName("OpenAPI Info 包含正确的 version")
    void infoVersion_isCorrect() {
        OpenAPI openAPI = config.customerServiceOpenAPI();
        Info info = openAPI.getInfo();
        assertThat(info).isNotNull();
        assertThat(info.getVersion()).isEqualTo("1.0.0");
    }

    @Test
    @DisplayName("OpenAPI Info 包含正确的 description")
    void infoDescription_isCorrect() {
        OpenAPI openAPI = config.customerServiceOpenAPI();
        Info info = openAPI.getInfo();
        assertThat(info).isNotNull();
        assertThat(info.getDescription()).contains("Spring AI Alibaba");
        assertThat(info.getDescription()).contains("智能客服");
    }

    @Test
    @DisplayName("OpenAPI Info 包含联系人信息")
    void infoContact_isConfigured() {
        OpenAPI openAPI = config.customerServiceOpenAPI();
        assertThat(openAPI.getInfo().getContact()).isNotNull();
        assertThat(openAPI.getInfo().getContact().getName()).isEqualTo("yysf1949");
    }

    @Test
    @DisplayName("OpenAPI 包含所有预期的 Tag 定义")
    void tags_containExpectedEntries() {
        OpenAPI openAPI = config.customerServiceOpenAPI();
        List<Tag> tags = openAPI.getTags();
        assertThat(tags).isNotNull();
        assertThat(tags).hasSizeGreaterThanOrEqualTo(6);

        List<String> tagNames = tags.stream().map(Tag::getName).toList();
        assertThat(tagNames).contains(
                "Agent", "QA", "Ingest",
                "KB Versions", "Document Versions", "Tool Catalog");
    }
}
