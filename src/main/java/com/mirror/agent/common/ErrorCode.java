package com.mirror.agent.common;

import lombok.Getter;

/**
 * 全局错误码（docs/error.md）。
 *
 * <p>6 位编码 = 错误级别(1) + 模块编码(2) + 具体错误编号(3)。</p>
 * <p>级别：0 成功 / 1 参数错误 / 2 业务错误 / 3 权限认证 / 4 资源不存在 / 5 系统错误 / 6 第三方服务错误。</p>
 * <p>模块：00 通用 01 会话调度 02 语言分析 03 对话交互 04 信息检索 05 任务执行
 * 06 合规校验 07 画像报告 08 基础设施 09 接口层。</p>
 */
@Getter
public enum ErrorCode {

    /* ---------- 0 成功 ---------- */
    SUCCESS("000000", "成功"),

    /* ---------- 1 参数错误 ---------- */
    PARAM_INVALID("100001", "请求参数非法"),
    PARAM_MISSING("100002", "缺少必要参数"),
    PARAM_FORMAT_ERROR("109001", "请求体格式错误"),

    /* ---------- 2 业务错误 ---------- */
    USERNAME_EXISTS("200001", "用户名已存在"),
    SESSION_EXPIRED("201001", "会话已过期或已结束"),
    SCHEDULE_PLAN_FAILED("201002", "执行链路规划失败"),
    ANALYSIS_LOW_CONFIDENCE("202001", "语义分析置信度不足，需要用户澄清"),
    ANALYSIS_PARSE_FAILED("202002", "标签结构化解析失败"),
    CHAT_REGENERATE_EXCEEDED("203001", "回复重生成次数超限"),
    RETRIEVAL_EMPTY("204001", "未检索到相关数据"),
    TASK_STATE_ILLEGAL("205001", "非法的任务状态流转"),
    TASK_PARAM_MISSING("205002", "任务参数缺失，需引导用户补充"),
    TASK_RETRY_EXCEEDED("205003", "任务重试次数超限"),
    COMPLIANCE_INPUT_REJECT("206001", "输入内容不合规，已拦截"),
    COMPLIANCE_OUTPUT_REJECT("206002", "输出内容不合规，已拦截"),
    COMPLIANCE_HIGH_RISK("206003", "识别到高危风险，已触发正向干预"),
    PROFILE_DATA_INSUFFICIENT("207001", "历史数据不足，结论仅供参考"),
    REPORT_GENERATE_FAILED("207002", "报告生成失败"),
    REPORT_CONTRACT_VIOLATION("207003", "报告契约校验未通过"),

    /* ---------- 3 权限认证错误 ---------- */
    UNAUTHORIZED("300001", "未登录或令牌已失效"),
    FORBIDDEN("300002", "权限不足"),
    USER_DISABLED("300003", "账号已被禁用"),
    LOGIN_FAILED("300004", "用户名或密码错误"),

    /* ---------- 4 资源不存在 ---------- */
    RESOURCE_NOT_FOUND("400001", "请求的资源不存在"),
    USER_NOT_FOUND("400002", "用户不存在"),
    CONVERSATION_NOT_FOUND("401001", "会话不存在"),
    MESSAGE_NOT_FOUND("401002", "消息不存在"),
    TASK_NOT_FOUND("405001", "任务不存在"),
    REPORT_NOT_FOUND("407001", "报告不存在"),
    KNOWLEDGE_DOC_NOT_FOUND("404001", "知识文档不存在"),

    /* ---------- 5 系统错误 ---------- */
    SYSTEM_ERROR("500001", "系统内部异常"),

    /* ---------- 6 第三方服务错误 ---------- */
    AI_MODEL_ERROR("600001", "大模型服务调用失败"),
    EMBEDDING_ERROR("600002", "向量嵌入服务调用失败"),
    DB_ERROR("608001", "数据库服务异常"),
    REDIS_ERROR("608002", "缓存服务异常"),
    MILVUS_ERROR("608003", "向量数据库服务异常");

    private final String code;
    private final String message;

    ErrorCode(String code, String message) {
        this.code = code;
        this.message = message;
    }
}
