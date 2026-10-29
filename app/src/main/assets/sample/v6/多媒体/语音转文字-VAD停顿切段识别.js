// 语音转文字·VAD 停顿切段识别：主线程只管录音→喂 VAD（200ms 停顿=一段），
// 识别在另一个线程排队跑，喂音频永不阻塞。段在静音处切，词不会被劈开；
// 段与段之间的音频照样进 VAD，说话不停就不切，跨段不丢内容。
// MAX_SEG_S 宁大勿小：到点强切会劈词。模型下载见「语音转文字-内置sherpa引擎.js」头注释。

// ================= 可改的变量 =================
var SRC = "system"        // "system"=内录 | "mic"=外录（麦克风）
var PAUSE_S = 0.2         // 静音多久算一段结束
var MAX_SEG_S = 30        // 单段最长秒数（到点强切会劈词，宁大勿小）
var RUN_MS = 30000        // 脚本总运行时长，到点自动退出
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
})
log("[VAD] 引擎=" + (ld.available ? "就绪" : "失败") + "，来源=" + (SRC === "mic" ? "麦克风" : "内录") +
    "，停顿 " + (PAUSE_S * 1000) + "ms 切段，单段上限 " + MAX_SEG_S + "s")

var vs = media.asr.vadStartStream({
    model: "asset:asr-model/silero_vad.onnx",
    sampleRate: SAMPLE_RATE,
    minSilenceDuration: PAUSE_S,
    maxSpeechDuration: MAX_SEG_S,
})
log("[VAD] VAD 会话 id=" + vs.id)

log("[VAD] 请在弹窗授权" + (SRC === "mic" ? "录音" : "屏幕录制"))
media.requestRecordPermission()
var rec = media.record.start({ source: SRC, format: "pcm", sampleRate: SAMPLE_RATE, channels: CHANNELS })
log("[VAD] 录音中: " + rec.state + "，" + (RUN_MS / 1000) + "s 后退出")

// 段队列：主线程产、工作线程识别，识别慢也不影响喂音频
var queue = new java.util.concurrent.LinkedBlockingQueue()
var worker = threads.start(function () {
    var segNo = 0
    while (true) {
        var seg = queue.poll(1, java.util.concurrent.TimeUnit.SECONDS)
        if (seg === null || seg === undefined) continue
        if (seg.done) break
        segNo++
        var t0 = Date.now()
        try {
            var r = media.asr.transcribePcm({ base64: seg.b64 }, { sampleRate: seg.sr, channels: seg.ch })
            log("[VAD] 段" + segNo + " [" + seg.start + "~" + seg.end + "ms " + ((seg.end - seg.start) / 1000).toFixed(1) + "s] 识别" +
                (Date.now() - t0) + "ms → 「" + (r.text || "(空)") + "」")
        } catch (e) {
            log("[VAD] 段" + segNo + " 识别异常: " + e)
        }
    }
    log("[VAD] 识别线程收工，共 " + segNo + " 段")
})

var deadline = Date.now() + RUN_MS
var segCount = 0
var fedBytes = 0
while (Date.now() < deadline) {
    var chunk = media.record.readPcm(6400)   // 200ms 块
    if (chunk && chunk.base64 && chunk.base64.length > 0) {
        fedBytes += chunk.bytes
        var fr = media.asr.vadFeed(vs.id, chunk, { sampleRate: SAMPLE_RATE, channels: CHANNELS })
        var segs = fr.segments || []
        for (var i = 0; i < segs.length; i++) {
            segCount++
            log("[VAD] 切出段" + segCount + " [" + segs[i].startMs + "~" + segs[i].endMs + "ms]，排队识别")
            queue.put({ done: false, b64: segs[i].base64, sr: SAMPLE_RATE, ch: CHANNELS, start: segs[i].startMs, end: segs[i].endMs })
        }
    } else {
        sleep(30)
    }
}

media.record.stop()
var tail = media.asr.vadFinish(vs.id)   // 收尾 flush：说话没停够 0.2s 的残余段也吐出来，不丢
var tailSegs = tail.segments || []
for (var j = 0; j < tailSegs.length; j++) {
    segCount++
    queue.put({ done: false, b64: tailSegs[j].base64, sr: SAMPLE_RATE, ch: CHANNELS, start: tailSegs[j].startMs, end: tailSegs[j].endMs })
}
queue.put({ done: true })
worker.join()
log("[VAD] 结束。共切 " + segCount + " 段，喂入 " + fedBytes + "B (" + ((fedBytes / 32000) | 0) + "s)")
