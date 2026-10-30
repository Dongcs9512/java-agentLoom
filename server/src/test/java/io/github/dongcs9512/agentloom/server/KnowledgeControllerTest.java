package io.github.dongcs9512.agentloom.server;

import io.github.dongcs9512.agentloom.rag.EmbeddingModel;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.StandardCharsets;

import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "agentloom.rag.enabled=true",
        "agentloom.rag.embedding.provider=siliconflow"
})
@AutoConfigureMockMvc
class KnowledgeControllerTest {

    @TestConfiguration
    static class TestConfig {
        @Bean
        @Primary
        public EmbeddingModel testEmbeddingModel() {
            return new EmbeddingModel() {
                @Override
                public float[] embed(String text) {
                    return new float[]{1, 0, 0, 0};
                }

                @Override
                public int dimension() {
                    return 4;
                }
            };
        }
    }

    @Autowired
    MockMvc mockMvc;

    private static MediaType json() {
        return new MediaType("application", "json", StandardCharsets.UTF_8);
    }

    private static String json(String s) {
        return s;
    }

    @Test
    void ingestThenSearchFindsDocument() throws Exception {
        mockMvc.perform(post("/api/kb/documents").contentType(json())
                        .content(json("{\"docId\":\"refund\",\"text\":\"退款政策：签收后 7 天内可无理由退款。\"}")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.chunkCount").value(1));

        mockMvc.perform(post("/api/kb/search").contentType(json())
                        .content(json("{\"query\":\"退款\",\"topK\":3}")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value("refund#0"))
                .andExpect(jsonPath("$[0].text").value("退款政策：签收后 7 天内可无理由退款。"));
    }

    @Test
    void deleteDocumentRemovesFromSearch() throws Exception {
        mockMvc.perform(post("/api/kb/documents").contentType(json())
                        .content(json("{\"docId\":\"tmp\",\"text\":\"临时文档内容\"}")))
                .andExpect(status().isOk());

        mockMvc.perform(delete("/api/kb/documents/tmp")).andExpect(status().isOk());

        mockMvc.perform(post("/api/kb/search").contentType(json())
                        .content(json("{\"query\":\"临时\"}")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].id", not(hasItem("tmp#0"))));
    }

    @Test
    void ingestWithoutDocIdReturns400() throws Exception {
        mockMvc.perform(post("/api/kb/documents").contentType(json())
                        .content(json("{\"text\":\"没有 docId\"}")))
                .andExpect(status().isBadRequest());
    }

    @Test
    void searchWithBlankQueryReturnsEmptyArray() throws Exception {
        mockMvc.perform(post("/api/kb/search").contentType(json()).content(json("{\"query\":\"\"}")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }
}
