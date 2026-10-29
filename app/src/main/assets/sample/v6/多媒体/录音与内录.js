/**
 * 录音 / 内录 / 波形采集示例。
 *
 * - 麦克风录音：直接可用
 * - 系统内录：Android 10+，第一次会弹「屏幕录制」授权框；
 *   授权后 App 会一直持有这个投影，之后任何脚本再内录都不会重复弹框
 *   （跨脚本复用，和截图授权同一套机制）。
 *
 * 录音跑在 App 进程级单例 + 前台服务里，脚本结束录音不会中断；
 * 另一个脚本随时可以调用 media.record.stop() 收尾并拿到结果。
 *
 * 波形数据用 media.record.readPcm() 拉：PCM16LE，同时给 base64 和 samples。
 * 注意它是「消费式」的：读走就从环形缓冲里删掉，没人读且缓冲满了会丢最旧的，
 * 丢弃量看 state().droppedBytes / readPcm().droppedBytes。
 */
const RECORD_SECONDS = 6;

if (!media.record.hasPermission()) {
    toastLog("申请录音权限…");
    if (!media.record.ensurePermission()) {
        toastLog("没有录音权限，脚本退出");
        exit();
    }
}

function sleepWithLevel(seconds) {
    for (let i = 0; i < seconds; i++) {
        sleep(1000);
        let s = media.record.state();
        log("电平 " + s.level + " dBFS, 峰值 " + s.peakDb + ", 有声 " + s.soundDetected +
            ", 静音 " + s.silentMs + "ms, 缓冲 " + s.bufferedBytes + "B");
    }
}

/** 从 samples 算 RMS（dBFS），和录音引擎的判法一致，可用来核对。 */
function rmsDb(samples) {
    if (!samples || samples.length === 0) return -120;
    let sum = 0;
    for (let i = 0; i < samples.length; i++) {
        let v = samples[i] / 32768;
        sum += v * v;
    }
    return Math.max(-120, 10 * Math.log(sum / samples.length) / Math.LN10);
}

/** 把波形采样点写成 CSV（一行一个采样点），便于 Excel / 脚本再分析。 */
function dumpSamplesToCsv(chunk, file) {
    let lines = ["index,value"];
    for (let i = 0; i < chunk.samples.length; i++) {
        lines.push(i + "," + chunk.samples[i]);
    }
    files.write(file, lines.join("\n"));
}
// ---------- 1. 麦克风：录音 + 实时波形 ----------
toastLog("开始麦克风录音 " + RECORD_SECONDS + " 秒");
let session = media.record.start({
    source: "mic",
    format: "wav",
    path: "demo_mic.wav",
    sampleRate: 16000,
    channels: 1,
});
log("录音文件: " + session.path + "  " + session.sampleRate + "Hz/" + session.channels + "ch");

// 边录边拉波形：每 500ms 拉一次，统计 RMS，顺便留最后一段存 CSV
let lastChunk = null;
for (let i = 0; i < RECORD_SECONDS * 2; i++) {
    sleep(500);
    let chunk = media.record.readPcm(32000); // 一次最多 32000 字节（16k 单声道 = 1 秒）
    if (chunk) {
        log("波形 " + chunk.bytes + "B, seq=" + chunk.seq + ", RMS " + rmsDb(chunk.samples).toFixed(1) + " dBFS");
        lastChunk = chunk;
    }
}
if (lastChunk) {
    let csv = files.getSdcardPath() + "/demo_mic_wave.csv";
    dumpSamplesToCsv(lastChunk, csv);
    toastLog("最后一段波形已存 " + csv + "（" + lastChunk.samples.length + " 个采样点）");
}

let result = media.record.stop();
media.scanFile(result.path);
toastLog("麦克风录音结束: " + result.path);
log("时长 " + result.durationMs + "ms, 字节 " + result.bytes + ", 峰值 " + result.peakDb + " dBFS, 错误 " + result.error);

// ---------- 2. 只分析、不落盘：format 用 none ----------
// 适合「判断有没有声音」或把波形直接 WS 外送，不产生音频文件。
toastLog("监听 3 秒，判断有没有声音（不落盘）");
media.record.start({
    source: "mic",
    format: "none",
    sampleRate: 16000,
    channels: 1,
    silenceThresholdDb: -45,
});
let heardNow = media.record.waitForSound(3000, -45);
let liveChunk = media.record.readPcm(16000);
log("听到声音: " + heardNow + ", 峰值 " + media.record.state().peakDb + " dBFS, 本轮波形 " +
    (liveChunk ? liveChunk.bytes + "B" : "无"));
media.record.stop();

// ---------- 3. 系统内录：先判断系统有没有在出声，再录音 ----------
log("系统当前是否在播放声音: " + media.record.isSystemAudioActive());

if (!media.record.isSystemAudioActive()) {
    toastLog("系统没在出声，先播放点音乐再内录…");
    sleep(3000);
}

toastLog("开始内录 " + RECORD_SECONDS + " 秒，请让其他 App 播放声音…");
session = media.record.start({
    source: "system",
    format: "mp3",
    path: "demo_system.mp3",
    sampleRate: 44100,
    channels: 2,
    bitRate: 128,
    // 默认只录媒体/游戏声音，可按需追加:
    // usages: ["media", "game", "voice_communication"]
});
log("录音文件: " + session.path);

// 等到真的有声音（最长 5 秒），再拉一段波形确认幅度
let heard = media.record.waitForSound(5000, -50);
log("waitForSound: " + heard + ", 当前电平 " + media.record.level() + " dBFS");
sleep(1000);
let sysChunk = media.record.readPcm(16384);
if (sysChunk) {
    log("内录波形 " + sysChunk.bytes + "B, " + sysChunk.sampleRate + "Hz/" + sysChunk.channels + "ch, RMS " +
        rmsDb(sysChunk.samples).toFixed(1) + " dBFS, 丢弃 " + sysChunk.droppedBytes + "B");

    // 外送示例：base64 直接发给 WebSocket（把 ws:// 换成你的地址即可）。
    // let ws = web.newWebSocket("ws://192.168.1.2:8765");
    // ws.send(sysChunk.base64);
}

sleepWithLevel(RECORD_SECONDS);

result = media.record.stop();
media.scanFile(result.path);
toastLog("内录结束: " + result.path);
log("时长 " + result.durationMs + "ms, 字节 " + result.bytes + ", 峰值 " + result.peakDb + " dBFS, 错误 " + result.error);

// 再强调一次跨脚本行为：
// 如果本脚本异常退出，录音不会停，任何脚本执行 media.record.stop() 即可收尾，
// 用 media.record.state() 能看到当前状态、电平和上一次的结果。
