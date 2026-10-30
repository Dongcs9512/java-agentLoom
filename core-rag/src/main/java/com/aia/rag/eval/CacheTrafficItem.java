package com.aia.rag.eval;

/**
 * 一条模拟流量。
 *
 * @param groupId 同一问法的不同措辞共享同一个 groupId；个案描述各自唯一
 */
public record CacheTrafficItem(String groupId, String text) {
}
