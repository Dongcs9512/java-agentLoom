package com.aia.rag;

import java.util.ArrayList;
import java.util.List;

public final class TextChunker {

    public List<String> chunk(String text, int maxChars, int overlapChars) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        if (maxChars <= 0) {
            throw new IllegalArgumentException("maxChars 必须大于 0");
        }
        if (text.length() <= maxChars) {
            return List.of(text);
        }
        List<String> chunks = new ArrayList<>();
        int step = Math.max(1, maxChars - overlapChars);
        int start = 0;
        while (start < text.length()) {
            int end = Math.min(start + maxChars, text.length());
            chunks.add(text.substring(start, end));
            if (end >= text.length()) {
                break;
            }
            start += step;
        }
        return chunks;
    }
}
