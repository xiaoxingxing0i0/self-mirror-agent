package com.mirror.agent.common;

import lombok.Data;
import org.slf4j.MDC;

import java.io.Serializable;

/**
 * 统一响应结构。code 见 {@link ErrorCode}（docs/error.md 6 位编码规范）。
 */
@Data
public class Result<T> implements Serializable {

    private String code;
    private String message;
    private T data;
    private String traceId;

    public static <T> Result<T> ok(T data) {
        return build(ErrorCode.SUCCESS, data);
    }

    public static <T> Result<T> ok() {
        return build(ErrorCode.SUCCESS, null);
    }

    public static <T> Result<T> fail(ErrorCode errorCode) {
        return build(errorCode, null);
    }

    public static <T> Result<T> fail(ErrorCode errorCode, String message) {
        Result<T> result = build(errorCode, null);
        result.setMessage(message);
        return result;
    }

    public static <T> Result<T> fail(String code, String message) {
        Result<T> result = new Result<>();
        result.setCode(code);
        result.setMessage(message);
        result.setTraceId(MDC.get("traceId"));
        return result;
    }

    private static <T> Result<T> build(ErrorCode errorCode, T data) {
        Result<T> result = new Result<>();
        result.setCode(errorCode.getCode());
        result.setMessage(errorCode.getMessage());
        result.setData(data);
        result.setTraceId(MDC.get("traceId"));
        return result;
    }
}
