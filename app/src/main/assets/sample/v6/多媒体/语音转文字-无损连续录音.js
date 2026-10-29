// 语音转文字·无损连续录音识别：内置 sherpa-onnx 流式引擎，全程一个流连续喂音频。
// 句末停顿（endpoint）自动定稿一句——在静音处切，永不把词劈成两半；
// 每 REPORT_MS 汇报一次进行中文本，结束时 flush 最后一句。适合「录音转文字」场景。
// 引擎收尾会按官方示例补尾静音（paraformer 0.8s / zipformer 0.66s），保证最后一个词完整。
// 模型下载见「语音转文字-内置sherpa引擎.js」头注释。

// ================= 可改的变量 =================
var SRC = "system"        // "system"=内录 | "mic"=外录（麦克风）
var REPORT_MS = 5000      // 每多少毫秒汇报一次当前识别快照（0=不汇报，只出定稿句）
var SENTENCE_SILENCE_S = 0.8   // 句末停顿阈值：静音多久算一句话结束（endpoint rule1）
var RUN_MS = 30000        // 脚本总运行时长，到点自动退出（收尾会 flush 最后一句）
var SAMPLE_RATE = 16000
var CHANNELS = 1
var MODEL_DIR = "/sdcard/asr-model"
// =============================================

var ld = media.asr.loadBuiltin({
    type: "zipformer", modelDir: MODEL_DIR,
    encoder: "encoder-epoch-99-avg-1.int8.onnx",
    decoder: "decoder-epoch-99-avg-1.onnx",
    joiner: "joiner-epoch-99-avg-1.int8.onnx",
    tokens: "tokens.txt", numThreads: 2,
    decodingMethod: "greedy_search",
    enableEndpoint: true,
    rule1: { mustContainNonSilence: true, minTrailingSilence: SENTENCE_SILENCE_S, minUtteranceLength: 0 },
    rule2: { mustContainNonSilence: true, minTrailingSilence: SENTENCE_SILENCE_S, minUtteranceLength: 0 },
    rule3: { mustContainNonSilence: false, minTrailingSilence: 0, minUtteranceLength: 0 },
})
log("[LIVE] 引擎=" + (ld.available ? "就绪" : "失败") + "，来源=" + (SRC === "mic" ? "麦克风" : "内录") + "，句末静音 " + (SENTENCE_SILENCE_S * 1000) + "ms 定稿")

log("[LIVE] 请在弹窗授权" + (SRC === "mic" ? "录音" : "屏幕录制"))
media.requestRecordPermission()
var rec = media.record.start({ source: SRC, format: "pcm", sampleRate: SAMPLE_RATE, channels: CHANNELS })
log("[LIVE] 录音中: " + rec.state + "，" + (RUN_MS / 1000) + "s 后退出")

var stream = media.asr.startStream()
var seenSegs = 0          // 已打印过的定稿句数
var fedBytes = 0
var lastReport = Date.now()

var deadline = Date.now() + RUN_MS
var reportDeadline = REPORT_MS > 0 ? Date.now() + REPORT_MS : Infinity
while (Date.now() < deadline) {
    var chunk = media.record.readPcm(12800)   // 400ms 块
    if (chunk && chunk.base64 && chunk.base64.length > 0) {
        fedBytes += chunk.bytes
        var snap = media.asr.feed(stream.id, chunk, { sampleRate: SAMPLE_RATE, channels: CHANNELS })
        var segs = snap.segments || []
        while (seenSegs < segs.length) {
            var s = segs[seenSegs]
            log("[LIVE] 定稿句" + (seenSegs + 1) + " [" + s.startMs + "~" + s.endMs + "ms] → 「" + s.text + "」")
            seenSegs++
        }
        if (Date.now() >= reportDeadline) {
            reportDeadline = Date.now() + REPORT_MS
            log("[LIVE] 快照: 进行中「" + (snap.partial || "") + "」 已定稿 " + segs.length + " 句")
        }
    } else {
        // 空闲期也顺手汇报一次快照，避免纯静音时 5s 汇报失效
        if (Date.now() >= reportDeadline) {
            reportDeadline = Date.now() + REPORT_MS
            log("[LIVE] 快照: 无新音频")
        }
        sleep(40)
    }
}

media.record.stop()
var fin = media.asr.finish(stream.id)   // flush 尾巴：未满一句停顿的残余也定稿，不丢
var segs = fin.segments || []
while (seenSegs < segs.length) {
    var s2 = segs[seenSegs]
    log("[LIVE] 定稿句" + (seenSegs + 1) + " [" + s2.startMs + "~" + s2.endMs + "ms] → 「" + s2.text + "」")
    seenSegs++
}
if (fin.partial && fin.text) log("[LIVE] 收尾 flush → 「" + fin.text + "」")
log("[LIVE] 结束。共定稿 " + seenSegs + " 句，喂入 " + fedBytes + "B (" + ((fedBytes / 32000) | 0) + "s)")
