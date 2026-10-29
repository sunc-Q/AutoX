// VAD 切段 + GTCRN 语音增强。
// VAD/GTCRN 模型已内置 APK（assets/asr-model/，共 1.2MB），默认 "asset:" 路径开箱即用；
// 也可换外置模型（/sdcard 下的绝对路径或相对 modelDir 的文件名），换版本不影响 JS 写法：
//   Silero VAD:   https://hf-mirror.com/csukuangfj/sherpa-onnx-silero-vad  （silero_vad.onnx，0.64MB）
//   GTCRN 增强:   https://hf-mirror.com/csukuangfj/sherpa-onnx-online-gtcrn （gtcrn_simple.onnx，约 5MB）
//
// 「VAD 切段 + 离线模型」是安卓上把非流式模型（paraformer-offline/sensevoice）
// 做成准实时的标准形态：VAD 吐段 → 段送整段识别 → 段间可上屏。

const VAD = { model: "asset:asr-model/silero_vad.onnx" }
const GTCRN = { model: "asset:asr-model/gtcrn_simple.onnx" }

// 1) 文件增强：GTCRN 降噪，输出 16k 单声道 WAV（缺省 原名-denoised.wav）
const rec = media.record.start({ source: "mic", format: "wav", sampleRate: 16000, channels: 1 })
sleep(4000)
const recorded = media.record.stop()
const den = media.asr.denoiseFile(recorded.path, GTCRN)
log("增强完成: " + den.outPath + " (" + den.ms + "ms)")

// 2) 文件 VAD 切段：每段起止 ms + base64 PCM
const cut = media.asr.vadSegmentFile(den.outPath, VAD)
log("切出 " + cut.segments.length + " 段:")
cut.segments.forEach(s => log("  [" + s.startMs + "~" + s.endMs + "ms] " + s.base64.length + "B(base64)"))

// 3) VAD 切段 + 离线模型 = 准实时上屏（不用流式模型也能边说边出字）
media.asr.loadBuiltin({
    type: "paraformer-offline",
    modelDir: "/sdcard/asr-model/sherpa-onnx-paraformer-zh-2024-03-09",
    model: "model.int8.onnx",
    tokens: "tokens.txt",
})
const vadStream = media.asr.vadStartStream(VAD)
media.record.start({ source: "system", format: "none", sampleRate: 44100, channels: 2 })
try {
    const deadline = Date.now() + 12000
    while (Date.now() < deadline) {
        const chunk = media.record.readPcm(32768)
        if (!chunk) {
            sleep(100)
            continue
        }
        const r = media.asr.vadFeed(vadStream.id, chunk, {
            sampleRate: chunk.sampleRate,
            channels: chunk.channels
        })
        r.segments.forEach(seg => {
            // 段的 base64 是 16k 单声道 PCM16，直接送整段识别
            const result = media.asr.transcribePcm(seg.base64, { sampleRate: 16000, channels: 1 })
            log("[" + seg.startMs + "~" + seg.endMs + "ms] " + result.text)
        })
    }
    media.asr.vadFinish(vadStream.id).segments.forEach(seg => {
        const result = media.asr.transcribePcm(seg.base64, { sampleRate: 16000, channels: 1 })
        log("[收尾 " + seg.startMs + "~" + seg.endMs + "ms] " + result.text)
    })
} finally {
    media.asr.vadCancel(vadStream.id)
    media.record.stop()
}

// 4) 实时增强流：麦克风逐块过 GTCRN 再喂 ASR（噪声大时用）
// const ds = media.asr.denoiseStartStream(GTCRN)
// const chunk = media.record.readPcm(32768)
// const clean = media.asr.denoiseFeed(ds.id, chunk, { sampleRate: chunk.sampleRate, channels: chunk.channels })
// media.asr.feed(streamId, clean.base64, { sampleRate: clean.sampleRate, channels: 1 })
// media.asr.denoiseFinish(ds.id)
