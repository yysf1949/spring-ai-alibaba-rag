package io.github.yysf1949.rag.agent.web;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.tags.Tag;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

/**
 * OpenAPI / Swagger UI 全局配置。
 *
 * <p>swagger-ui.html 在 springdoc-openapi-starter-webmvc-ui 2.6.0 自动注册；
 * 本类只声明 API 元数据（标题 / 描述 / 联系人 / 标签）。</p>
 */
@Configuration("agentOpenApiConfig")
public class OpenApiConfig {

    private static final String BEARER_SCHEME = "bearer-jwt";

    @Bean
    @Primary
    public OpenAPI customerServiceOpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("AI Agent 客服系统 API")
                        .description("基于 Spring AI Alibaba 的智能客服 Agent，支持 22+ 工具、4 级风险管控、"
                                + "多存储后端（Redis / H2 / MySQL）、多消息渠道")
                        .version("0.1.0-SNAPSHOT")
                        .contact(new Contact()
                                .name("yysf1949")
                                .url("https://github.com/yysf1949/spring-ai-alibaba-rag"))
                        .license(new License()
                                .name("Apache 2.0")
                                .url("https://www.apache.org/licenses/LICENSE-2.0")))
                .components(new Components()
                        .addSecuritySchemes(BEARER_SCHEME, new SecurityScheme()
                                .type(SecurityScheme.Type.APIKEY)
                                .in(SecurityScheme.In.HEADER)
                                .name("X-Tenant-Id")
                                .description("Tenant identifier propagated by the gateway. "
                                        + "Authoritative — the body field is ignored.")))
                .addSecurityItem(new SecurityRequirement().addList(BEARER_SCHEME))
                .addTagsItem(new Tag().name("Agent").description("Agent 对话 / 调用接口"))
                .addTagsItem(new Tag().name("QA").description("在线问答接口"))
                .addTagsItem(new Tag().name("Ingest").description("文档异步入库接口"))
                .addTagsItem(new Tag().name("KB Versions").description("知识库版本生命周期"))
                .addTagsItem(new Tag().name("Document Versions").description("文档版本生命周期"))
                .addTagsItem(new Tag().name("Tool Catalog").description("Agent 工具目录"));
    }
}
