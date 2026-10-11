package com.mirror.agent.tool;

import java.util.Map;

/**
 * 外部 HTTP 调用统一抽象（硬性规则 5：禁止裸 RestTemplate / HttpURLConnection）。
 * 所有出站请求必须经过本接口，便于统一超时、日志、鉴权与熔断。
 */
public interface ApiClient {

    <T> T get(String url, Map<String, String> headers, Class<T> responseType);

    <T> T post(String url, Map<String, String> headers, Object body, Class<T> responseType);
}
