package com.mirror.agent.report.contract;

import com.mirror.agent.common.BizException;
import com.mirror.agent.common.ErrorCode;

import java.util.List;

/**
 * 报告契约校验失败异常：携带全部违规的错误路径（JSON Pointer，如 {@code $.sections[0].heading}）。
 *
 * <p>继承 {@link BizException}（错误码 {@link ErrorCode#REPORT_CONTRACT_VIOLATION}），
 * 可被现有 GlobalExceptionHandler 直接兜底为标准响应。</p>
 */
public class ContractViolationException extends BizException {

    /** 校验失败的路径列表（稳定排序，便于断言与日志检索） */
    private final List<String> errorPaths;

    /**
     * @param message    汇总信息（包含每条错误的路径与原因）
     * @param errorPaths 全部违规路径
     */
    public ContractViolationException(String message, List<String> errorPaths) {
        super(ErrorCode.REPORT_CONTRACT_VIOLATION, message);
        this.errorPaths = List.copyOf(errorPaths);
    }

    /**
     * @return 不可变的违规路径列表
     */
    public List<String> getErrorPaths() {
        return errorPaths;
    }
}
