package com.aia.server;

import com.aia.rag.KnowledgeBase;
import com.aia.rag.SearchHit;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/kb")
@ConditionalOnProperty(prefix = "aia.rag", name = "enabled", havingValue = "true")
public class KnowledgeController {

    private final KnowledgeBase knowledgeBase;

    public KnowledgeController(KnowledgeBase knowledgeBase) {
        this.knowledgeBase = knowledgeBase;
    }

    public record IngestRequest(String docId, String text) {}

    public record SearchRequest(String query, Integer topK) {}

    @PostMapping("/documents")
    public ResponseEntity<?> ingest(@RequestBody IngestRequest request) {
        if (request.docId() == null || request.docId().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "docId 不能为空"));
        }
        if (request.text() == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "text 不能为空"));
        }
        int chunkCount = knowledgeBase.addDocument(request.docId(), request.text());
        return ResponseEntity.ok(Map.of("docId", request.docId(), "chunkCount", chunkCount));
    }

    @DeleteMapping("/documents/{docId}")
    public void delete(@PathVariable String docId) {
        knowledgeBase.deleteDocument(docId);
    }

    @PostMapping("/search")
    public List<SearchHit> search(@RequestBody SearchRequest request) {
        int topK = request.topK() == null ? 5 : request.topK();
        return knowledgeBase.search(request.query(), topK);
    }
}
