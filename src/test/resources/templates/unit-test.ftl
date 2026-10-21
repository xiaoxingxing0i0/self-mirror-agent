<!DOCTYPE html>
<html xmlns="http://www.w3.org/1999/xhtml">
<head><meta charset="UTF-8" /><title>${title}</title></head>
<body>
<p>${summary}</p>
<#if sections?size == 0>
<p class="empty">（无章节）</p>
<#else>
<ul><#list sections as s><li>${s.heading}</li></#list></ul>
</#if>
</body>
</html>
