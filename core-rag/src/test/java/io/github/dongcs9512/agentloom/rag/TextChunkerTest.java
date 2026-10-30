package io.github.dongcs9512.agentloom.rag;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TextChunkerTest {

    private final TextChunker chunker = new TextChunker();

    @Test
    void emptyTextReturnsEmptyList() {
        assertTrue(chunker.chunk("", 10, 2).isEmpty());
        assertTrue(chunker.chunk("   ", 10, 2).isEmpty());
    }

    @Test
    void shortTextReturnsSingleChunk() {
        List<String> chunks = chunker.chunk("hello", 10, 2);
        assertEquals(1, chunks.size());
        assertEquals("hello", chunks.get(0));
    }

    @Test
    void chunksWithOverlap() {
        List<String> chunks = chunker.chunk("0123456789", 5, 2);
        assertEquals(List.of("01234", "34567", "6789"), chunks);
    }
}
