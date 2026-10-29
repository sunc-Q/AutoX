// 系统 TTS（文字转语音）：android.speech.tts 的薄封装。
// speak / synthesizeToFile 都是阻塞到完成（或超时）才返回；相对路径按脚本目录解析。
//
// 直调 Java：UtteranceProgressListener 是抽象类、Rhino 无法继承，完成回调由 TtsManager 代持；
// 其余能力（voices/getLanguage/setLanguage 等）仍可经
//   Packages.android.speech.tts.TextToSpeech
// 走原生方法（桥接函数没覆盖到的参数都能拿到）。

// 1) 初始化（阻塞至就绪，最长 8s）；不传 engine 用系统默认引擎
const st = media.tts.init({
    // engine: "com.google.android.tts",  // 指定引擎包名（可用 media.tts.status().engines 查）
    language: "zh-CN",
    // pitch: 1.0, rate: 1.0, volume: 1.0,
})
log("TTS 状态: " + JSON.stringify(st))
if (!st.available) {
    toastLog("TTS 初始化失败: " + (st.error || ""))
    exit()
}

// 2) 查本机可用引擎
log("可用引擎: " + JSON.stringify(st.engines))

// 3) 阻塞朗读：等播完才返回，返回 { ok, ms }
const r = media.tts.speak("今天天气不错，适合出去走走。", { rate: 1.0 })
log("朗读完成: " + JSON.stringify(r))

// 4) 合成到 WAV 文件（44 字节标准头 + PCM16），可直接喂给 media.asr.transcribeFile 做闭环验证
const f = media.tts.synthesizeToFile("今天天气不错。", "./tts-out.wav")
log("合成结果: " + JSON.stringify(f))
if (f.ok) log("WAV 大小: " + f.bytes + "B，路径: " + f.path)

// 5) 按次覆盖参数
media.tts.speak("语速可以加快。", { rate: 1.5 })
media.tts.speak("音调也可以变高。", { pitch: 1.4 })

// 6) 收尾：stop 打断当前朗读；shutdown 释放引擎实例
media.tts.stop()
media.tts.shutdown()
