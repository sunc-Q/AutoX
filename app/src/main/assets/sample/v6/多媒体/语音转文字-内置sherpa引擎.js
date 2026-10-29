// 语音转文字：内置 sherpa-onnx 引擎（zipformer 流式 / paraformer / sensevoice）。
// 模型不打包在 APK 里，路径由 loadBuiltin 的配置指定（相对路径按 /sdcard 解析）。
//
// 模型下载（hf-mirror，选一套即可）：
//   zipformer 流式双语:
//     https://hf-mirror.com/csukuangfj/sherpa-onnx-streaming-zipformer-bilingual-zh-en-2023-02-20
//     （取 encoder-epoch-99-avg-1.onnx / decoder-epoch-99-avg-1.onnx / joiner-epoch-99-avg-1.onnx / tokens.txt，
//       int8 版文件名带 .int8，体积约一半）
//   流式 paraformer 双语:
//     https://hf-mirror.com/csukuangfj/sherpa-onnx-streaming-paraformer-bilingual-zh-en
//   离线 paraformer: https://hf-mirror.com/csukuangfj/sherpa-onnx-paraformer-zh-2024-03-09
//   离线 sensevoice: https://hf-mirror.com/csukuangfj/SenseVoiceSmall
//
// 直调 Java：本引擎的底层类在宿主 APK 内，Rhino 引擎可直接
//   Packages.com.k2fsa.sherpa.onnx.OnlineRecognizer / OfflineRecognizer / Vad / …
//   走原生方法（桥接函数没覆盖到的参数都能拿到）。

// 1) 加载引擎（模型路径换成你设备上的实际文件）
media.asr.loadBuiltin({
    type: "zipformer",   // zipformer | paraformer | paraformer-offline | sensevoice | 省略=自动推断
    modelDir: "/sdcard/asr-model/sherpa-onnx-streaming-zipformer-bilingual-zh-en-2023-02-20",
    encoder: "encoder-epoch-99-avg-1.int8.onnx",
    decoder: "decoder-epoch-99-avg-1.onnx",
    joiner: "joiner-epoch-99-avg-1.int8.onnx",
    tokens: "tokens.txt",
    // numThreads: 2, provider: "cpu", debug: false,
    // ruleFsts: "/sdcard/asr-model/itn_zh_number.fst",  // 数字转阿拉伯数字（可选）
})
log("ASR 状态: " + JSON.stringify(media.asr.status()))

// 2) 整段识别：录一段麦克风 WAV 再识别（WAV 自动识别采样率/声道）
media.record.start({ source: "mic", format: "wav", sampleRate: 16000, channels: 1 })
sleep(4000)
const recorded = media.record.stop()
log("录音文件: " + recorded.path)
log("整段识别: " + media.asr.transcribeFile(recorded.path).text)

// 3) 流式识别：内录 PCM 分块喂给引擎，边录边出字（端点检测自动切句）
media.record.start({
    source: "system",
    format: "none",
    sampleRate: 44100,
    channels: 2,
    tapBufferBytes: 4 * 1024 * 1024
})
const stream = media.asr.startStream()
try {
    const deadline = Date.now() + 12000
    while (Date.now() < deadline) {
        const chunk = media.record.readPcm(65536)
        if (!chunk) {
            sleep(100)
            continue
        }
        const snap = media.asr.feed(stream.id, chunk, {
            sampleRate: chunk.sampleRate,
            channels: chunk.channels
        })
        if (snap.partial) log("实时: " + snap.partial)
        if (snap.segments.length) {
            snap.segments.forEach(s => log("定稿[" + s.startMs + "~" + s.endMs + "ms]: " + s.text))
        }
    }
    log("最终: " + media.asr.finish(stream.id).text)
} finally {
    media.asr.cancel(stream.id)
    media.record.stop()
}
