package com.aia.rag;

/**
 * 带版本号的检索缓存：清理时递增版本号，其它实例据此判断自己本地的缓存是否已过期。
 *
 * <p>多实例部署时，一台机器写入知识库会清空共享缓存，但别的机器进程内的缓存清不掉；
 * 它们定期比对版本号，发现变化就清自己的本地缓存。
 */
public interface VersionedRetrievalCache extends RetrievalCache {

    /** 当前版本号，每次 clear 都会递增。 */
    long version();
}
