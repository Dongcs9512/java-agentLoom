package com.aia.core.session;

/**
 * 需要向用户收集的一项信息。
 *
 * @param name     槽位标识，写入会话时使用
 * @param ask      缺这一项时该怎么问用户
 * @param required 是否必填
 */
public record Slot(String name, String ask, boolean required) {
}
