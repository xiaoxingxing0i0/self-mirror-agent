package com.mirror.agent.controller;

import com.mirror.agent.common.Result;
import com.mirror.agent.entity.KnowledgeDoc;
import com.mirror.agent.service.KnowledgeService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * RAG 知识库管理接口：文档列表 / 新增文档 / 触发向量化。
 * 知识库为全局共享的心理学科普内容（非用户私有数据）。
 */
@RestController
@RequestMapping("/api/knowledge")
@RequiredArgsConstructor
public class KnowledgeController {

    private final KnowledgeService knowledgeService;

    @Data
    public static class DocRequest {
        @NotBlank(message = "标题不能为空")
        private String title;
        @NotBlank(message = "分类不能为空")
        private String category;
        @NotBlank(message = "内容不能为空")
        private String content;
    }

    @GetMapping
    public Result<List<KnowledgeDoc>> list() {
        return Result.ok(knowledgeService.listDocs());
    }

    @PostMapping
    public Result<Long> add(@Valid @RequestBody DocRequest request) {
        KnowledgeDoc doc = knowledgeService.addDoc(
                request.getTitle(), request.getCategory(), request.getContent());
        return Result.ok(doc.getId());
    }

    @PostMapping("/vectorize")
    public Result<Integer> vectorizePending() {
        return Result.ok(knowledgeService.vectorizePending());
    }
}
