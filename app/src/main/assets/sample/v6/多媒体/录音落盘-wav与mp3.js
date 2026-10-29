// 录音落盘为 WAV / MP3 文件：内录、外录都可以，格式和来源都是变量。
// - format:"wav"  = 无压缩 PCM WAV（44 字节标准头 + PCM16）
// - format:"mp3"  = lame 实时编码（bitRate 8~320 kbps，默认 128）
// - format:"pcm"  = 裸 PCM 无头（喂 ASR 用）
// - format:"none" = 只进内存环形缓冲不落盘（配 readPcm 实时消费）
// path 可指定；省略自动命名 record_时间戳.扩展名。
// 录音在 App 进程级单例 + 前台服务里，脚本结束不会中断，任何脚本可 media.record.stop() 收尾。

// ================= 可改的变量 =================
var SRC = "mic"        // "mic"=外录（麦克风）| "system"=内录（Android 10+，首次弹屏幕录制授权）
var FORMAT = "wav"     // "wav" | "mp3" | "pcm" | "none"
var SECONDS = 8        // 录多长时间
var PATH = ""          // 留空=自动命名 record_时间戳.扩展名；例 "/sdcard/asr-lab/rec.mp3"
var SAMPLE_RATE = 0    // 0=按来源取默认（内录 44100/双声道，麦克风 16000/单声道）
var BIT_RATE = 128     // 仅 mp3 生效，kbps
// =============================================

if (SRC === "system") {
    log("[REC] 内录需要屏幕录制授权，请在弹窗点允许")
    media.requestRecordPermission()
}
var opt = { source: SRC, format: FORMAT, bitRate: BIT_RATE }
if (PATH) opt.path = PATH
if (SAMPLE_RATE > 0) { opt.sampleRate = SAMPLE_RATE; opt.channels = 2 }  // 指定采样率时同步给立体声，避免不匹配
var rec = media.record.start(opt)
log("[REC] 开始: " + rec.path + "，录 " + SECONDS + "s（" + FORMAT.toUpperCase() + "）")

for (var i = 0; i < SECONDS; i++) {
    sleep(1000)
    var s = media.record.state()
    log("[REC] " + (i + 1) + "s 电平 " + s.level + " dBFS 已写 " + s.bytes + "B")
}

var fin = media.record.stop()
if (fin && fin.path) {
    var len = files.exists(fin.path) ? new java.io.File(fin.path).length() : -1
    log("[REC] 完成: " + fin.path + " " + len + "B，时长 " + (fin.durationMs / 1000).toFixed(1) + "s" +
        (fin.error ? "，error=" + fin.error : ""))
} else {
    log("[REC] 停止返回: " + JSON.stringify(fin))
}
