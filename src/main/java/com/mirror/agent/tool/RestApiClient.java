package com.mirror.agent.tool;

import com.mirror.agent.common.BizException;
import com.mirror.agent.common.ErrorCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.Map;

/**
 * ApiClient 默认实现，基于 Spring RestClient（非裸 RestTemplate），
 * 由 Spring 容器统一管理（硬性规则 2：横切组件禁止手工 new）。
 */
@Slf4j
@Component
public class RestApiClient implements ApiClient {

    private final RestClient restClient;

    public RestApiClient(RestClient.Builder builder) {
        this.restClient = builder.build();
    }

    @Override
    public <T> T get(String url, Map<String, String> headers, Class<T> responseType) {
        try {
            return restClient.get()
                    .uri(url)
                    .headers(h -> headers.forEach(h::set))
                    .retrieve()
                    .body(responseType);
        } catch (Exception e) {
            log.error("GET 调用失败: url={}", url, e);
            throw new BizException(ErrorCode.SYSTEM_ERROR, "外部服务调用失败: " + url, e);
        }
    }

    @Override
    public <T> T post(String url, Map<String, String> headers, Object body, Class<T> responseType) {
        try {
            return restClient.post()
                    .uri(url)
                    .headers(h -> headers.forEach(h::set))
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(responseType);
        } catch (Exception e) {
            log.error("POST 调用失败: url={}", url, e);
            throw new BizException(ErrorCode.SYSTEM_ERROR, "外部服务调用失败: " + url, e);
        }
    }
}
