package com.mirror.agent.report.contract;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.mirror.agent.common.BizException;
import com.mirror.agent.common.ErrorCode;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 报告契约校验器（契约层）。
 *
 * <p>加载 {@code classpath:/report-schema/report.schema.json}（Draft 2020-12）校验入参 JSON：
 * 失败抛出 {@link ContractViolationException}（携带全部违规路径，稳定排序）；
 * 通过后用 Jackson 反序列化为 {@link ReportContract}（忽略未知字段）。</p>
 *
 * <p>无状态、线程安全，可作为 Spring Bean 注入，也可在测试中直接 new。</p>
 */
@Slf4j
@Component
public class ContractValidator {

    /** schema 资源在 classpath 中的固定位置 */
    public static final String SCHEMA_LOCATION = "/report-schema/report.schema.json";

    /** 校验器专用 ObjectMapper：与 JsonUtil 同源 Jackson，但额外关闭 FAIL_ON_UNKNOWN_PROPERTIES */
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    private final JsonSchema schema;

    /**
     * 从 classpath 加载 schema 并构建校验器。
     *
     * @throws IllegalStateException schema 资源缺失或解析失败（属部署问题，应尽早暴露）
     */
    public ContractValidator() {
        JsonSchemaFactory factory = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012);
        try (InputStream in = ContractValidator.class.getResourceAsStream(SCHEMA_LOCATION)) {
            if (in == null) {
                throw new IllegalStateException("报告契约 schema 缺失，期望 classpath 资源: " + SCHEMA_LOCATION);
            }
            this.schema = factory.getSchema(in);
        } catch (IOException e) {
            throw new IllegalStateException("报告契约 schema 加载失败: " + SCHEMA_LOCATION, e);
        }
    }

    /**
     * 校验契约 JSON 并反序列化为 DTO。
     *
     * @param contractJson 符合 schema 的 JSON 字符串（非空）
     * @return 校验通过的 {@link ReportContract}
     * @throws ContractViolationException JSON 不合法或违反 schema；异常信息包含所有错误路径
     * @throws BizException               反序列化阶段失败（校验通过但 DTO 不匹配，理论不应发生）
     */
    public ReportContract validate(String contractJson) {
        if (contractJson == null || contractJson.isBlank()) {
            throw new ContractViolationException("契约 JSON 为空", List.of("$"));
        }
        JsonNode node;
        try {
            node = MAPPER.readTree(contractJson);
        } catch (JsonProcessingException e) {
            log.debug("契约 JSON 解析失败", e);
            throw new ContractViolationException(
                    "契约不是合法 JSON: " + e.getOriginalMessage(), List.of("$"));
        }

        Set<ValidationMessage> errors = schema.validate(node);
        if (!errors.isEmpty()) {
            // 排序保证同一输入的错误输出确定（可测试性要求）
            List<ValidationMessage> sorted = errors.stream()
                    .sorted(Comparator.comparing(ContractValidator::pathOf))
                    .toList();
            String detail = sorted.stream()
                    .map(vm -> pathOf(vm) + " -> " + vm.getMessage())
                    .collect(Collectors.joining("; "));
            List<String> paths = sorted.stream().map(ContractValidator::pathOf).toList();
            log.warn("报告契约校验未通过: {} 处违规 [{}]", sorted.size(), detail);
            throw new ContractViolationException(
                    "报告契约校验未通过（" + sorted.size() + " 处）: " + detail, paths);
        }

        try {
            return MAPPER.convertValue(node, ReportContract.class);
        } catch (IllegalArgumentException e) {
            // schema 与 DTO 不一致时才会走到这里，属于开发期问题
            throw new BizException(ErrorCode.SYSTEM_ERROR, "契约反序列化失败（schema/DTO 不一致）: " + e.getMessage(), e);
        }
    }

    /**
     * 提取违规路径：required/additionalProperties 类错误的位置是父对象，需拼接 property 才指向具体字段。
     */
    private static String pathOf(ValidationMessage vm) {
        String loc = String.valueOf(vm.getInstanceLocation());
        String prop = vm.getProperty();
        return (prop == null || prop.isBlank()) ? loc : loc + "." + prop;
    }
}
