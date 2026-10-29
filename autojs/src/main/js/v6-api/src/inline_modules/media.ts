const media = Object.create(runtime.media) as Autox.Media

/**
 * 录音/内录的 JS 友好封装：Java 侧收发 JSON 字符串，这里直接返回对象。
 * 录音跑在 App 进程级单例里，脚本结束后仍会继续，任何脚本都能 stop/查询。
 */
media.record = {
    /**
     * 开始录音。
     * - source: 'mic'（默认）麦克风 / 'system' 系统内录（需 Android 10+，首次会弹屏幕录制授权）
     * - format: 'wav'（默认）/ 'pcm' / 'mp3'，也可由 path 后缀推断
     * - path: 输出路径，相对路径按脚本目录解析；省略则自动生成 record_时间戳
     * - sampleRate / channels / bitRate(kbps) / usages
     */
    start(options?: Autox.AudioRecordOptions): Autox.AudioRecordResult {
        return JSON.parse(runtime.media.startRecord(JSON.stringify(options || {})))
    },
    /** 停止录音并返回结果（path/durationMs/bytes/peakDb/error 等）。 */
    stop(): Autox.AudioRecordResult & { state: string } {
        return JSON.parse(runtime.media.stopRecord())
    },
    pause(): void {
        runtime.media.pauseRecord()
    },
    resume(): void {
        runtime.media.resumeRecord()
    },
    isRecording(): boolean {
        return runtime.media.isRecording()
    },
    /** 当前状态：state / path / durationMs / level，空闲时带 last（上一次结果）。 */
    state(): Autox.AudioRecordState {
        return JSON.parse(runtime.media.getRecordState())
    },
    /** 最近一次采集缓冲的 RMS 电平（dBFS，-120 表示无声）。 */
    level(): number {
        return runtime.media.getRecordLevel()
    },
    /**
     * 拉取一段波形数据（PCM16LE 原始采样率/声道），没有可读数据时返回 null。
     * 返回对象带 base64（可直接 WS 外送）和 samples（number[]，便于分析/存文本）。
     * 录音停止后仍可继续拉取缓冲里剩余的部分。
     */
    readPcm(maxBytes?: number): Autox.AudioRecordPcm | null {
        const chunk = runtime.media.readRecordPcm(maxBytes || 65536)
        if (!chunk) return null
        return {
            base64: chunk.base64,
            samples: toNumberArray(chunk.samples),
            bytes: chunk.bytes,
            sampleRate: chunk.sampleRate,
            channels: chunk.channels,
            seq: chunk.seq,
            droppedBytes: chunk.droppedBytes
        }
    },
    /** 只要采样点（number[]），方便 Math.max / JSON.stringify 存文本。 */
    readSamples(maxBytes?: number): number[] | null {
        const chunk = media.record.readPcm(maxBytes)
        return chunk ? chunk.samples : null
    },
    /** 阻塞等待出现声音（level >= thresholdDb），超时返回 false。不要在 UI 线程调用。 */
    waitForSound(timeoutMs?: number, thresholdDb?: number): boolean {
        return runtime.media.waitRecordSound(timeoutMs || 10000, thresholdDb === undefined ? -50 : thresholdDb)
    },
    /** 系统当前是否有 App 在出声，不需要录音授权。 */
    isSystemAudioActive(): boolean {
        return runtime.media.isSystemAudioActive()
    },
    hasPermission(): boolean {
        return runtime.media.hasRecordPermission()
    },
    /** 申请麦克风权限并等待结果（最长 15 秒）。 */
    ensurePermission(): boolean {
        return runtime.media.requestRecordPermission()
    }
}

function toNumberArray(javaShorts: any): number[] {
    const out: number[] = []
    if (!javaShorts) return out
    for (let i = 0; i < javaShorts.length; i++) {
        out.push(javaShorts[i])
    }
    return out
}

/**
 * 语音转文字（接口已预留，需先加载 ASR 引擎）。
 * 引擎未就绪时 status().available === false，其余调用会抛出带说明的异常。
 * 输入直接吃 record.readPcm() 的 base64，也可以喂录音存下来的 WAV 文件；
 * 流式识别可把内录/麦克风的 PCM 分块 feed 进去，边录边出字。
 */
media.asr = {
    /** 当前引擎状态；未注册时 available=false 并带 error 说明。 */
    status(): Autox.AsrStatus {
        return JSON.parse(runtime.media.asrStatusJson())
    },
    /**
     * 从已安装的插件 APK 加载 ASR 引擎。
     * className 需实现 com.stardust.autojs.core.audio.asr.AsrEngine，且是 public 无参构造。
     */
    loadPluginEngine(packageName: string, className: string, config?: any): Autox.AsrStatus {
        return JSON.parse(runtime.media.asrLoadPluginEngine(packageName, className, JSON.stringify(config || {}) || '{}'))
    },
    unload(): void {
        runtime.media.asrUnloadEngine()
    },
    /** 整段识别。data 传 base64 字符串或 record.readPcm() 的返回值。 */
    transcribePcm(data: string | { base64: string }, options?: Autox.AsrPcmOptions): Autox.AsrResult {
        const opts: Autox.AsrPcmOptions = options || {}
        return JSON.parse(runtime.media.asrTranscribePcmJson(
            asrBase64(data), opts.sampleRate || 16000, opts.channels || 1
        ))
    },
    /** 整段识别文件：WAV 自动识别采样率/声道，裸 PCM 需传 options.sampleRate。 */
    transcribeFile(path: string, options?: Autox.AsrPcmOptions): Autox.AsrResult {
        const opts: Autox.AsrPcmOptions = options || {}
        return JSON.parse(runtime.media.asrTranscribeFileJson(
            path, opts.sampleRate || 0, opts.channels || 0
        ))
    },
    /** 开一个流式会话，返回 { id, sampleRate }。 */
    startStream(): Autox.AsrStreamInfo {
        return JSON.parse(runtime.media.asrStartStreamJson())
    },
    /** 送一段 PCM，返回当前快照：text（定稿+实时假设）/ partial / segments / isFinal。 */
    feed(id: number, data: string | { base64: string }, options?: Autox.AsrPcmOptions): Autox.AsrSnapshot {
        const opts: Autox.AsrPcmOptions = options || {}
        return JSON.parse(runtime.media.asrFeedStreamJson(
            id, asrBase64(data), opts.sampleRate || 16000, opts.channels || 1
        ))
    },
    /** 收尾，返回最终文本（会话随之结束）。 */
    finish(id: number): Autox.AsrSnapshot {
        return JSON.parse(runtime.media.asrFinishStreamJson(id))
    },
    cancel(id: number): boolean {
        return runtime.media.asrCancelStream(id)
    },
    /**
     * 注册内置 sherpa-onnx 引擎（模型文件路径全部由配置指定，不打包在 APK 里）。
     * 例：loadBuiltin({ type: 'zipformer', modelDir: '/sdcard/asr-model',
     *   encoder: 'encoder.int8.onnx', decoder: 'decoder.onnx', joiner: 'joiner.int8.onnx', tokens: 'tokens.txt' })
     */
    loadBuiltin(config?: any): Autox.AsrStatus {
        return JSON.parse(runtime.media.asrLoadBuiltinEngineJson(JSON.stringify(config || {})))
    },
    /** 文件整段按 VAD 切段（Silero），段带起止 ms；config.includePcm 缺省 true 时附带 base64 PCM。 */
    vadSegmentFile(path: string, config: any): Autox.VadFileResult {
        return JSON.parse(runtime.media.asrVadSegmentFileJson(path, JSON.stringify(config || {})))
    },
    /** 开流式 VAD 会话，返回 { id, sampleRate }。 */
    vadStartStream(config: any): Autox.VadStreamInfo {
        return JSON.parse(runtime.media.asrVadStartStreamJson(JSON.stringify(config || {})))
    },
    /** 喂一块 PCM，返回 { speech, segments:[{startMs,endMs,base64?}] }（本块内切完的段）。 */
    vadFeed(id: number, data: string | { base64: string }, options?: Autox.AsrPcmOptions): Autox.VadFeedResult {
        const opts: Autox.AsrPcmOptions = options || {}
        return JSON.parse(runtime.media.asrVadFeedJson(
            id, asrBase64(data), opts.sampleRate || 0, opts.channels || 0
        ))
    },
    /** 收尾 flush，吐出剩余段（会话随之结束）。 */
    vadFinish(id: number): Autox.VadFeedResult {
        return JSON.parse(runtime.media.asrVadFinishJson(id))
    },
    vadCancel(id: number): boolean {
        return runtime.media.asrVadCancel(id)
    },
    /** 整文件 GTCRN 增强，输出 16k 单声道 WAV（缺省写到 原名-denoised.wav）。 */
    denoiseFile(path: string, config: any): Autox.DenoiseFileResult {
        return JSON.parse(runtime.media.asrDenoiseFileJson(path, JSON.stringify(config || {})))
    },
    /** 整段 PCM GTCRN 增强：base64 进、base64 出（16k 单声道）。 */
    denoisePcm(data: string | { base64: string }, options?: Autox.AsrPcmOptions, config?: any): Autox.DenoiseAudioOut {
        const opts: Autox.AsrPcmOptions = options || {}
        return JSON.parse(runtime.media.asrDenoisePcmJson(
            asrBase64(data), opts.sampleRate || 0, opts.channels || 0, JSON.stringify(config || {})
        ))
    },
    /** 开流式增强会话，返回 { id, sampleRate, frameShiftSamples }。 */
    denoiseStartStream(config: any): Autox.DenoiseStreamInfo {
        return JSON.parse(runtime.media.asrDenoiseStartJson(JSON.stringify(config || {})))
    },
    /** 喂一块 PCM，返回本块增强后的 { base64, sampleRate }。 */
    denoiseFeed(id: number, data: string | { base64: string }, options?: Autox.AsrPcmOptions): Autox.DenoiseAudioOut {
        const opts: Autox.AsrPcmOptions = options || {}
        return JSON.parse(runtime.media.asrDenoiseFeedJson(
            id, asrBase64(data), opts.sampleRate || 0, opts.channels || 0
        ))
    },
    /** 收尾 flush，返回剩余增强音频（会话随之结束）。 */
    denoiseFinish(id: number): Autox.DenoiseAudioOut {
        return JSON.parse(runtime.media.asrDenoiseFinishJson(id))
    },
    denoiseCancel(id: number): boolean {
        return runtime.media.asrDenoiseCancel(id)
    }
}

/**
 * 系统 TTS（android.speech.tts）。
 * UtteranceProgressListener 是抽象类、Rhino 无法继承，完成回调由 TtsManager 代持，
 * speak/synthesizeToFile 都是阻塞到完成（或 timeoutMs 超时）才返回。
 */
media.tts = {
    /** 初始化（阻塞至就绪，最长 8s）。同一时刻只保留一个实例，重复 init 会先 shutdown。 */
    init(config: Autox.TtsConfig = {}): Autox.TtsStatus {
        return JSON.parse(runtime.media.ttsInitJson(JSON.stringify(config || {})))
    },
    /** 阻塞朗读直到播完；config 可按次覆盖 language/pitch/rate/volume/timeoutMs。 */
    speak(text: string, config?: Autox.TtsConfig): Autox.TtsSpeakResult {
        return JSON.parse(runtime.media.ttsSpeakJson(text, JSON.stringify(config || {})))
    },
    /** 阻塞合成到 WAV 文件（44 字节标准头 + PCM16），相对路径按脚本目录解析。 */
    synthesizeToFile(text: string, path: string, config?: Autox.TtsConfig): Autox.TtsFileResult {
        return JSON.parse(runtime.media.ttsSynthesizeToFileJson(text, path, JSON.stringify(config || {})))
    },
    stop(): boolean {
        return runtime.media.ttsStop()
    },
    shutdown(): void {
        runtime.media.ttsShutdown()
    },
    /** 当前实例状态；engines 列出本机全部 TTS 引擎。 */
    status(): Autox.TtsStatus {
        return JSON.parse(runtime.media.ttsStatusJson())
    },
}

function asrBase64(data: string | { base64: string }): string {
    return typeof data === 'string' ? data : data.base64
}

export default media

export type Media = Autox.Media
