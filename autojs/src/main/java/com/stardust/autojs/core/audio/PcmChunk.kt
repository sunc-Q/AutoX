package com.stardust.autojs.core.audio

/**
 * 一次 PCM 拉取的结果。字段是 public，Rhino 里可直接 `chunk.base64` / `chunk.samples[i]`。
 */
class PcmChunk(
    @JvmField val base64: String,
    @JvmField val samples: ShortArray,
    @JvmField val bytes: Int,
    @JvmField val sampleRate: Int,
    @JvmField val channels: Int,
    /** 本次录音累计写入的字节数（单调递增，可用来做去重/对账） */
    @JvmField val seq: Long,
    /** 因为消费太慢被丢弃的字节数 */
    @JvmField val droppedBytes: Long
)
