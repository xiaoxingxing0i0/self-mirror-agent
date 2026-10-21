<!DOCTYPE html>
<html xmlns="http://www.w3.org/1999/xhtml" xml:lang="zh-CN">
<head>
<meta charset="UTF-8" />
<title>${title}</title>
<style type="text/css">
  /* 页面设置：A4 + 页边距；页脚为页码，页眉为模板自带静态文案（动态字段只进正文，避免转义问题） */
  @page {
    size: A4;
    margin: 2.2cm 1.8cm 2.4cm 1.8cm;
    @top-center {
      content: "镜像分析报告";
      font-family: "Noto Sans CJK SC", sans-serif;
      font-size: 8pt;
      color: #999999;
    }
    @bottom-center {
      content: "第 " counter(page) " 页 / 共 " counter(pages) " 页";
      font-family: "Noto Sans CJK SC", sans-serif;
      font-size: 8pt;
      color: #999999;
    }
  }
  body {
    font-family: "Noto Sans CJK SC", sans-serif;
    font-size: 10.5pt;
    color: #222222;
    margin: 0;
  }
  h1 { font-size: 16pt; margin: 0 0 6pt 0; }
  h2 { font-size: 12pt; margin: 16pt 0 6pt 0; }
  .meta { font-size: 9pt; color: #666666; margin: 0 0 12pt 0; }
  .summary {
    border-left: 3pt solid #1976d2;
    background-color: #f5f9ff;
    padding: 8pt 10pt;
    margin: 0 0 8pt 0;
  }
  .empty { color: #999999; font-style: italic; }
  table { width: 100%; border-collapse: collapse; margin: 4pt 0 10pt 0; }
  thead { display: table-header-group; }
  th {
    background-color: #eeeeee;
    border: 0.5pt solid #cccccc;
    padding: 4pt 6pt;
    text-align: left;
    font-size: 9.5pt;
  }
  td {
    border: 0.5pt solid #cccccc;
    padding: 4pt 6pt;
    font-size: 9.5pt;
  }
  /* 状态列着色：class 由组装层的 statusClass 稳定映射（ok/warn/critical/none） */
  .status-ok { color: #2e7d32; }
  .status-warn { color: #ef6c00; font-weight: bold; }
  .status-critical { color: #c62828; font-weight: bold; }
  .status-none { color: #999999; }
  td.status { text-align: center; }
</style>
</head>
<body>
<h1>${title}</h1>
<p class="meta">报告编号：${reportId}<br />生成时间：${generatedAt}</p>
<#if summary?length gt 0>
<div class="summary">${summary}</div>
<#else>
<p class="empty">（无摘要）</p>
</#if>
<#if sections?size == 0>
<p class="empty">（本报告暂无章节数据）</p>
<#else>
<#list sections as section>
<h2>${section.heading}</h2>
<#if section.rows?size == 0>
<p class="empty">（本章节暂无数据行）</p>
<#else>
<table>
<thead>
<tr><th>指标</th><th>值</th><th>状态</th></tr>
</thead>
<tbody>
<#list section.rows as row>
<tr>
<td>${row.label}</td>
<td>${row.value}</td>
<td class="status status-${row.statusClass}">${row.statusLabel}</td>
</tr>
</#list>
</tbody>
</table>
</#if>
</#list>
</#if>
</body>
</html>
