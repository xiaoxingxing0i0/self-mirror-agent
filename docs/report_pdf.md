# PDF 报告模块(JSON 契约 → 模板渲染 → PDF)

Agent 服务的确定性 Tool:输入符合 JSON Schema 的报告契约 + 模板 ID,输出 PDF 字节流。
全链路无随机性、无网络/外部服务依赖,代码与展示内容解耦(展示内容只来自模板 + 渲染模型)。

## 四层结构

| 层 | 类 | 职责 |
|---|---|---|
| 契约层 | `report/contract/ContractValidator` | networknt json-schema-validator(Draft 2020-12)校验 `resources/report-schema/report.schema.json`;失败抛 `ContractViolationException`(含全部错误路径,如 `$.sections[0].heading`);通过后 Jackson 反序列化为 `ReportContract`(忽略未知字段) |
| 组装层 | `report/assemble/ReportModelAssembler` | 契约 → 渲染模型 Map。null 归一空串、`generatedAt` 按时区/格式配置格式化(缺失回退注入的 `Clock`)、枚举稳定展示名(穷尽 switch,新增枚举值编译失败强制同步)、null 集合归一空列表 |
| 渲染层 | `report/render/TemplateRenderer` | FreeMarker 单例 Configuration(UTF-8、XML 输出格式→插值转义、Locale.ROOT、number_format=computer)。模板经 `TemplateSource` 扩展点加载(当前 `ClasspathTemplateSource`:classpath:/templates/{id}.ftl;后续可换 DB/OSS 实现同一接口)。templateId 白名单校验 `^[a-z0-9][a-z0-9-]{0,63}$`,防路径穿越 |
| PDF 层 | `report/pdf/PdfConverter` | openhtmltopdf(PdfRendererBuilder)+PDFBox 2.0.24。字体从 classpath:/fonts/ 读入缓存(构造期 fail-fast,缺失抛含放置指引的异常)。输出 byte[] 或流式写 OutputStream |

入口:`report/ReportPdfTool#render(String contractJson, String templateId) → byte[]`
(另有 `renderTo(..., OutputStream)` 流式变体;templateId 传 null 用默认模板)。

## 错误语义

统一走现有 `BizException`/`ErrorCode` 体系(GlobalExceptionHandler 零改动兜底):

- `207003 REPORT_CONTRACT_VIOLATION` — 契约不合法,消息与 `getErrorPaths()` 含全部违规路径
- `207002 REPORT_GENERATE_FAILED` — 模板不存在/模板错误/渲染转换失败
- `100001 PARAM_INVALID` — 模板 ID 格式非法

## 如何新增一种报告模板

1. **契约(schema)**:若新报告字段结构不同,在 `resources/report-schema/` 新增 `{name}.schema.json`;结构相同则复用现有 schema。
2. **模板**:新增 `resources/templates/{name}.ftl`(name 即 templateId,需匹配 `[a-z0-9][a-z0-9-]*`)。
   - 必须是合法 XHTML:标签全闭合(`<br />`、`<meta ... />`)、根元素 `<html xmlns="http://www.w3.org/1999/xhtml">`;
   - 布局只用 CSS 2.1(table 排版),**禁止 flex/grid**(openhtmltopdf 不支持);
   - CSS 的 `font-family` 必须与配置 `mirror.report.font-family` 一致(默认 `Noto Sans CJK SC`),否则中文静默回退失败;
   - 页码/页眉用 `@page { @bottom-center { content: counter(page) " / " counter(pages); } }`(见 report.ftl 示例)。
   - 数据模型 key 契约见 `ReportModelAssembler` 类注释;空态占位文案写在模板里(如 `<#if sections?size == 0>`),不要在 Java 中硬编码。
3. **校验器注册**:若新增 schema,扩展 `ContractValidator`(当前单 schema 固定路径)传入对应 `SchemaLocation`,或按 templateId→schema 映射注入。
4. **测试**:在 `ReportPdfToolIT` 补充新模板全链路用例(sample JSON 放 templates/ 下)。

## 中文字体

- 当前字体:`resources/fonts/NotoSansSC-VF.ttf`(Noto Sans SC 区域子集,可变字体,TTF/glyf 轮廓,OFL 1.1 允许再分发)。
- 必须用 **TrueType(glyf)轮廓**:PDFBox 2.0.24 对 CFF 轮廓的 OTF(如 noto-cjk 官方 SubsetOTF)嵌入时不写 ToUnicode,
  PDF 虽能生成但文本不可抽取/不可复制。更换字体前先跑 `PdfConverterTest#chineseTextIsEmbeddedAndExtractable` 验证。
- 替换字体:放入新文件并调整 `mirror.report.font-classpath`;可变字体权重以默认实例(Regular)嵌入。

## 配置项(application.yml `mirror.report.*`)

`default-template` / `date-format` / `time-zone` / `font-classpath` / `font-family`。

## 本地验证

```bash
# 前置:JAVA_HOME=D:/java,mvn 用 ~/.m2/wrapper/dists 下的 3.9.16(本机无全局 mvn)
mvn -q compile
mvn test -Dtest='ContractValidatorTest,ReportModelAssemblerTest,TemplateRendererTest,PdfConverterTest,ReportPdfToolIT'
# 集成产物:target/report-sample.pdf(可直接打开目检中文、页码、状态着色)
```

## 已知限制

- openhtmltopdf 不支持 flexbox/grid/CSS3 复杂布局;仅 CSS 2.1 子集 + 部分 `@page` 扩展。
- PDF 元数据含生成时间戳,两次输出**不保证逐字节一致**;确定性指版面/文本内容一致。
- 本期只提供同步接口。异步(任务 ID + 独立线程池 + Redis 状态)预留:`ReportPdfTool` 保持无状态可并发调用,
  实施时另建 `ThreadPoolTaskExecutor`(CPU 密集,并发≤核数/2,勿用公共 `applicationTaskExecutor`),避免占用 `@EnableAsync` 默认池。
- 模板渲染异常信息含模板名/行列号,但复杂 CSS 解析错误(openhtmltopdf 抛的)定位粒度有限,模板改动建议先跑 IT。
