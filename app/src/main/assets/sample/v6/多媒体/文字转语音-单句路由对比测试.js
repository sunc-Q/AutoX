// TTS 单句输出口路由对比测试：听筒 / 扬声器。
// 会先列出本机全部输出口，再对单句做 setPreferredDevice 路由并回读 accepted 结果，
// 用于快速验证某台设备的路由是否生效；逐句列表播放见「文字转语音-扬声器听筒路由.js」。
// 原理：TextToSpeech 本身没有按次选输出口的 API，这里 synthToFile 后用 MediaPlayer
// 的 setPreferredDevice(AudioDeviceInfo) 指定路由（API 23+）。

// ================= 可改的变量 =================
var ROUTE = "speaker"   // "speaker"=扬声器 | "earpiece"=听筒
var TEXT = "这句走的是默认路由，用来对比。"
var RUN_LOOPS = 1       // 播几遍
// =============================================

var am = context.getSystemService(context.AUDIO_SERVICE);

function findDevice(type) {
    var ds = am.getDevices(android.media.AudioManager.GET_DEVICES_OUTPUTS);
    var names = [];
    for (var i = 0; i < ds.length; i++) {
        names.push(ds[i].getType() + ":" + ds[i].productName);
        if (ds[i].getType() === type) return ds[i];
    }
    log("[ROUTE] 本机输出口: " + names.join(", "));
    return null;
}

var targetType = ROUTE === "earpiece" ? android.media.AudioDeviceInfo.TYPE_BUILTIN_EARPIECE
                                      : android.media.AudioDeviceInfo.TYPE_BUILTIN_SPEAKER;
var dev = findDevice(targetType);
if (!dev) { log("[ROUTE] 找不到目标输出口 type=" + targetType + "，退出"); exit(); }
log("[ROUTE] 目标设备: " + dev.productName + " type=" + dev.getType() + " (" + (ROUTE === "earpiece" ? "听筒" : "扬声器") + ")");

var st = media.tts.init({ language: "zh-CN" });
if (!st.available) { log("[ROUTE] TTS 初始化失败"); exit(); }

var wav = "/sdcard/asr-lab/tts-route.wav";
files.ensureDir("/sdcard/asr-lab/");

for (var n = 0; n < RUN_LOOPS; n++) {
    var f = media.tts.synthesizeToFile(TEXT, wav);
    if (!f.ok) { log("[ROUTE] 合成失败: " + (f.error || "")); exit(); }

    var mp = new android.media.MediaPlayer();
    var attrs = new android.media.AudioAttributes.Builder()
        .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
        .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH)
        .build();
    mp.setAudioAttributes(attrs);
    mp.setDataSource(wav);
    mp.prepare();
    var accepted = mp.setPreferredDevice(dev);
    log("[ROUTE] setPreferredDevice(" + ROUTE + ") → " + accepted);
    mp.start();
    sleep(mp.getDuration() + 400);
    mp.stop();
    mp.release();
    log("[ROUTE] 第 " + (n + 1) + " 遍播完");
}

media.tts.shutdown();
log("[ROUTE] 结束。用耳朵确认：speaker=外放响，earpiece=贴耳朵才有声");
