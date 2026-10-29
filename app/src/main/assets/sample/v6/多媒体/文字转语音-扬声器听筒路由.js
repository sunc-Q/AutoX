// 系统 TTS 逐句播放 + 输出口路由：按 LINES 列表依次朗读，每句后停顿指定毫秒。
// ROUTE 指定输出口：speaker=扬声器 / earpiece=听筒（TextToSpeech 无逐句路由 API，
// 这里走 synthesizeToFile → MediaPlayer.setPreferredDevice，API 23+）。
// 基础用法见「文字转语音-系统TTS.js」。

// ================= 可改的变量 =================
var ROUTE = "speaker"   // "speaker"=扬声器 | "earpiece"=听筒
var LINES = [
    ["你好，这是语音播放测试。", 500],
    ["第二句话，语速稍快一点。", 1000],
    ["最后一句，播放结束。", 0],
]
var RATE = 1.0          // 语速
var PITCH = 1.0         // 音调
var VOLUME = 1.0        // 音量 0~1
var LANGUAGE = "zh-CN"
// =============================================

var am = context.getSystemService(context.AUDIO_SERVICE);
function findDevice(type) {
    var ds = am.getDevices(android.media.AudioManager.GET_DEVICES_OUTPUTS);
    for (var i = 0; i < ds.length; i++) if (ds[i].getType() === type) return ds[i];
    return null;
}
var targetType = ROUTE === "earpiece" ? android.media.AudioDeviceInfo.TYPE_BUILTIN_EARPIECE
                                      : android.media.AudioDeviceInfo.TYPE_BUILTIN_SPEAKER;
var dev = findDevice(targetType);
log("[TTS] 输出口=" + (ROUTE === "earpiece" ? "听筒" : "扬声器") + (dev ? "（已找到）" : "（未找到，走默认路由）"));

var st = media.tts.init({ language: LANGUAGE, rate: RATE, pitch: PITCH, volume: VOLUME })
log("[TTS] 引擎=" + (st.available ? "就绪" : "失败 " + (st.error || "")) + " language=" + st.language)
if (!st.available) exit()

var wav = "/sdcard/asr-lab/tts-play.wav";
files.ensureDir("/sdcard/asr-lab/");

function playFile(path, dev) {
    var mp = new android.media.MediaPlayer();
    var attrs = new android.media.AudioAttributes.Builder()
        .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
        .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH)
        .build();
    mp.setAudioAttributes(attrs);
    mp.setDataSource(path);
    mp.prepare();
    var ok = dev ? mp.setPreferredDevice(dev) : false;
    mp.start();
    sleep(mp.getDuration() + 300);
    mp.stop();
    mp.release();
    return ok;
}

for (var i = 0; i < LINES.length; i++) {
    var text = LINES[i][0]
    var gap = LINES[i][1] || 0
    log("[TTS] 第" + (i + 1) + "句: " + text)
    var f = media.tts.synthesizeToFile(text, wav)
    if (!f.ok) { log("[TTS]   合成失败: " + (f.error || "")); continue; }
    var routed = playFile(wav, dev)
    log("[TTS]   播完 " + f.ms + "ms 合成 + " + f.bytes + "B，路由=" + (routed ? ROUTE : "默认"))
    if (gap > 0 && i < LINES.length - 1) sleep(gap)
}

media.tts.shutdown()
log("[TTS] 全部播放完成")
