package com.aia.core.session;

import com.aia.llm.ChatMessage;

import java.util.List;

public interface ContextCompressor {

    /**
     * 上下文超阈值时压缩历史。
     *
     * @param existingSummary 上一次压缩留下的摘要，可为 null
     * @param messages        当前完整的消息历史
     * @return 压缩后的摘要与保留的消息；未触发压缩时原样返回
     */
    CompressedContext compress(String existingSummary, List<ChatMessage> messages);
}
