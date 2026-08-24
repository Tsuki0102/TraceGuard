package com.traceguard.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import springfox.documentation.builders.ApiInfoBuilder;
import springfox.documentation.builders.ParameterBuilder;
import springfox.documentation.builders.PathSelectors;
import springfox.documentation.builders.RequestHandlerSelectors;
import springfox.documentation.schema.ModelRef;
import springfox.documentation.service.ApiInfo;
import springfox.documentation.service.Contact;
import springfox.documentation.service.Parameter;
import springfox.documentation.spi.DocumentationType;
import springfox.documentation.spring.web.plugins.Docket;

import java.util.ArrayList;
import java.util.List;

/**
 * Knife4j(springfox) API 文档配置。
 * 访问入口：http://localhost:8080/api/doc.html
 * 除登录/注册外均需鉴权：先调登录接口获取 token，再在文档页"Authorize"中填入。
 */
@Configuration
public class Knife4jConfig {

    @Bean
    public Docket api() {
        // 全局 JWT 请求头（文档页右上角 Authorize 统一配置）
        List<Parameter> params = new ArrayList<>();
        params.add(new ParameterBuilder()
                .name("Authorization")
                .description("JWT 令牌，格式：Bearer <登录接口返回的token>")
                .modelRef(new ModelRef("string"))
                .parameterType("header")
                .required(false)
                .build());

        return new Docket(DocumentationType.SWAGGER_2)
                .apiInfo(apiInfo())
                .select()
                .apis(RequestHandlerSelectors.basePackage("com.traceguard.controller"))
                .paths(PathSelectors.any())
                .build()
                .globalOperationParameters(params);
    }

    private ApiInfo apiInfo() {
        return new ApiInfoBuilder()
                .title("TraceGuard 需求-代码追溯平台 API")
                .description("基于形式化验证的需求-代码一致性分析平台。核心流程：项目创建 -> 上传需求/代码 -> "
                        + "配置权重阈值 -> 执行分析 -> 查看一致性/缺陷/追溯结果。")
                .version("1.0.0")
                .contact(new Contact("TraceGuard", "", ""))
                .build();
    }
}
