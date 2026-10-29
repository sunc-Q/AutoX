import { invokeDefault } from '@/java'
const media = Autox.media
export class MediaPlayer {
    private _media = media.createMediaPlayer()
    get androidMediaPlayer(): any {
        return this._media
    }
    /**
     * @returns {number}当前播放位置。单位毫秒。
     */
    get currentPosition(): number {
        return this._media.getCurrentPosition()
    }
    /**
     * @returns {number} 音乐时长。单位毫秒。
     */
    get duration(): number {
        return this._media.getDuration()
    }
    get isPlaying(): boolean {
        return this._media.isPlaying()
    }
    async play(uri: string, volume?: number, looping?: boolean) {
        this.setDataSource(uri)
        if (volume) this.setVolume(volume)
        if (looping) this.setLooping(looping)
        await this.prepare()
        this.start()
    }
    pause() {
        this._media.pause()
    }
    async prepare() {
        await invokeDefault(this._media, "prepare", [])
    }
    prepareSync(): void {
        this._media.prepare()
    }
    release() {
        this._media.release()
    }
    reset() {
        this._media.reset()
    }
    async seekTo(msec: number) {
        await invokeDefault(this._media, "seekTo", [msec])
    }
    setDataSource(path: string) {
        this._media.setDataSource(path)
    }
    setLooping(looping: boolean) {
        this._media.setLooping(looping)
    }
    setScreenOnWhilePlaying(keep: boolean) {
        this._media.setScreenOnWhilePlaying(keep)
    }
    setVolume(leftVolume: number, rightVolume?: number) {
        if (rightVolume === undefined) rightVolume = leftVolume
        this._media.setVolume(leftVolume, rightVolume)
    }
    start() {
        this._media.start()
    }
    stop() {
        this._media.stop()
    }
}

export async function playMusic(uri: string, volume?: number, looping?: boolean): Promise<MediaPlayer> {
    const media = new MediaPlayer()
    await media.play(uri, volume, looping)
    return media
}

export function scanFile(file: string): void {
    media.scanFile(file)
}

export interface AudioRecordOptions {
    /** mic=麦克风（默认），system=系统内录（Android 10+） */
    source?: 'mic' | 'system'
    format?: 'wav' | 'pcm' | 'mp3' | 'none'
    /** 输出路径；相对路径落在 /sdcard 下，省略则自动生成 record_时间戳 */
    path?: string
    sampleRate?: number
    channels?: 1 | 2
    bitRate?: number
    usages?: (number | string)[]
    /** 波形环形缓冲大小（字节），默认 2MB，0~64MB */
    tapBufferBytes?: number
    /** 判定「有声音」的阈值（dBFS），默认 -50 */
    silenceThresholdDb?: number
}

export interface AudioRecordResult {
    state?: string
    path: string
    source: string
    format: string
    sampleRate: number
    channels: number
    bytes: number
    durationMs: number
    peakDb: number
    droppedBytes: number
    error?: string
}

/** 开始录音。录音跑在 App 进程级单例里，脚本结束后仍会继续。 */
export function startRecord(options?: AudioRecordOptions): AudioRecordResult {
    return JSON.parse(media.startRecord(JSON.stringify(options ?? {})))
}

/** 停止录音（任何脚本都可以调用），返回结果 */
export function stopRecord(): AudioRecordResult & { state: string } {
    return JSON.parse(media.stopRecord())
}

export function getRecordState(): any {
    return JSON.parse(media.getRecordState())
}

export function getRecordLevel(): number {
    return media.getRecordLevel()
}

export interface AudioRecordPcm {
    /** PCM16LE 的 Base64，可直接 WebSocket 外送 */
    base64: string
    /** 采样点（PCM16 有符号数），便于分析 */
    samples: number[]
    bytes: number
    sampleRate: number
    channels: number
    seq: number
    droppedBytes: number
}

/** 拉一段波形（PCM16LE，base64 + samples），无数据返回 null */
export function readRecordPcm(maxBytes = 65536): AudioRecordPcm | null {
    const json = media.readRecordPcmJson(maxBytes)
    const obj = JSON.parse(json)
    return obj.bytes > 0 ? obj : null
}

/** 只要采样点，便于 Math.max / JSON.stringify 存文本 */
export function readRecordSamples(maxBytes = 65536): number[] | null {
    const chunk = readRecordPcm(maxBytes)
    return chunk ? chunk.samples : null
}

/** 阻塞等待出现声音（level >= thresholdDb），超时返回 false。不要在 UI 线程调用 */
export function waitRecordSound(timeoutMs = 10000, thresholdDb = -50): boolean {
    return media.waitRecordSound(timeoutMs, thresholdDb)
}

/** 系统当前是否有 App 在出声，不需要录音授权 */
export function isSystemAudioActive(): boolean {
    return media.isSystemAudioActive()
}

export function isRecording(): boolean {
    return media.isRecording()
}

export function pauseRecord(): void {
    media.pauseRecord()
}

export function resumeRecord(): void {
    media.resumeRecord()
}

export function hasRecordPermission(): boolean {
    return media.hasRecordPermission()
}

export function requestRecordPermission(): boolean {
    return media.requestRecordPermission()
}

export interface AsrStatus {
    /** 引擎是否已注册；false 时其余 ASR 调用会抛异常 */
    available: boolean
    engine?: string | null
    /** 引擎要求的输入采样率，通常 16000 */
    sampleRate: number
    streaming?: boolean
    offline?: boolean
    languages?: string[]
    error?: string
}

export interface AsrPcmOptions {
    sampleRate?: number
    channels?: 1 | 2
}

export interface AsrSegment {
    text: string
    startMs: number
    endMs: number
}

export interface AsrResult {
    text: string
    segments: AsrSegment[]
    isFinal: boolean
    sampleRate: number
}

export interface AsrStreamInfo {
    id: number
    sampleRate: number
    streaming: boolean
}

export interface AsrSnapshot {
    id: number
    /** 已定稿段 + 当前实时假设 */
    text: string
    partial: string
    segments: AsrSegment[]
    isFinal: boolean
    fedMs: number
}

/** 语音转文字（接口已预留，需先加载 ASR 引擎） */
export function asrStatus(): AsrStatus {
    return JSON.parse(media.asrStatusJson())
}

/**
 * 从已安装的插件 APK 加载 ASR 引擎。
 * className 需实现 com.stardust.autojs.core.audio.asr.AsrEngine，且是 public 无参构造。
 */
export function asrLoadPluginEngine(packageName: string, className: string, config?: any): AsrStatus {
    return JSON.parse(media.asrLoadPluginEngine(packageName, className, JSON.stringify(config ?? {}) || '{}'))
}

export function asrUnload(): void {
    media.asrUnloadEngine()
}

/** 整段识别（base64 字符串或 {base64}，可直接用 readRecordPcm() 的返回值）。 */
export function asrTranscribePcm(data: string | { base64: string }, options: AsrPcmOptions = {}): AsrResult {
    return JSON.parse(media.asrTranscribePcmJson(
        asrBase64(data), options.sampleRate || 16000, options.channels || 1
    ))
}

/** 整段识别文件：WAV 自动识别采样率/声道，裸 PCM 需传 options.sampleRate。 */
export function asrTranscribeFile(path: string, options: AsrPcmOptions = {}): AsrResult {
    return JSON.parse(media.asrTranscribeFileJson(
        path, options.sampleRate || 0, options.channels || 0
    ))
}

export function asrStartStream(): AsrStreamInfo {
    return JSON.parse(media.asrStartStreamJson())
}

export function asrFeed(id: number, data: string | { base64: string }, options: AsrPcmOptions = {}): AsrSnapshot {
    return JSON.parse(media.asrFeedStreamJson(
        id, asrBase64(data), options.sampleRate || 16000, options.channels || 1
    ))
}

export function asrFinish(id: number): AsrSnapshot {
    return JSON.parse(media.asrFinishStreamJson(id))
}

export function asrCancel(id: number): boolean {
    return media.asrCancelStream(id)
}

export interface AsrEngineConfig {
    /** zipformer（流式 transducer）/ paraformer（流式）/ paraformer-offline / sensevoice；缺省自动推断 */
    type?: string
    /** 相对文件名的基准目录（绝对路径或相对 /sdcard） */
    modelDir?: string
    encoder?: string
    decoder?: string
    joiner?: string
    /** 离线模型的单文件（paraformer-offline / sensevoice） */
    model?: string
    tokens?: string
    ruleFsts?: string
    ruleFars?: string
    hotwords?: string
    hotwordsScore?: number
    sampleRate?: number
    featureDim?: number
    numThreads?: number
    provider?: string
    debug?: boolean
    enableEndpoint?: boolean
    rule1?: { mustContainNonSilence?: boolean, minTrailingSilence?: number, minUtteranceLength?: number }
    rule2?: { mustContainNonSilence?: boolean, minTrailingSilence?: number, minUtteranceLength?: number }
    rule3?: { mustContainNonSilence?: boolean, minTrailingSilence?: number, minUtteranceLength?: number }
    /** sensevoice 语言：auto/zh/en/ja/ko/yue */
    language?: string
    useItn?: boolean
    name?: string
}

/**
 * 注册内置 sherpa-onnx 引擎（模型文件路径全部由本配置指定，不打包在 APK 里）。
 * 例：asrLoadBuiltin({ type: 'zipformer', modelDir: '/sdcard/asr-model',
 *   encoder: 'encoder-epoch-12-avg-2-chunk-16-left-64.int8.onnx', decoder: 'decoder-….onnx',
 *   joiner: 'joiner-….int8.onnx', tokens: 'tokens.txt' })
 */
export function asrLoadBuiltin(config: AsrEngineConfig = {}): AsrStatus {
    return JSON.parse(media.asrLoadBuiltinEngineJson(JSON.stringify(config)))
}

export interface VadConfig {
    /** silero_vad.onnx 路径 */
    model: string
    modelDir?: string
    /** 8000 或 16000 */
    sampleRate?: number
    threshold?: number
    minSilenceDuration?: number
    minSpeechDuration?: number
    windowSize?: number
    maxSpeechDuration?: number
    numThreads?: number
    /** 切出的段是否带 base64 PCM（默认 true） */
    includePcm?: boolean
}

export interface VadSegment {
    startMs: number
    endMs: number
    ms?: number
    base64?: string
}

export interface VadFileResult {
    sampleRate: number
    segments: VadSegment[]
}

export interface VadStreamInfo {
    id: number
    sampleRate: number
}

export interface VadFeedResult {
    /** 会话内当前是否处于语音中 */
    speech: boolean
    segments: VadSegment[]
}

/** 文件整段按 VAD 切：返回每段起止 ms（includePcm 时附带 base64 PCM16） */
export function asrVadSegmentFile(path: string, config: VadConfig): VadFileResult {
    return JSON.parse(media.asrVadSegmentFileJson(path, JSON.stringify(config)))
}

export function asrVadStartStream(config: VadConfig): VadStreamInfo {
    return JSON.parse(media.asrVadStartStreamJson(JSON.stringify(config)))
}

export function asrVadFeed(id: number, data: string | { base64: string }, options: AsrPcmOptions = {}): VadFeedResult {
    return JSON.parse(media.asrVadFeedJson(
        id, asrBase64(data), options.sampleRate || 0, options.channels || 0
    ))
}

export function asrVadFinish(id: number): VadFeedResult {
    return JSON.parse(media.asrVadFinishJson(id))
}

export function asrVadCancel(id: number): boolean {
    return media.asrVadCancel(id)
}

export interface DenoiseConfig {
    /** gtcrn onnx 路径 */
    model: string
    modelDir?: string
    numThreads?: number
    provider?: string
    debug?: boolean
    /** 文件增强时的输出 WAV 路径；缺省为 原名-denoised.wav */
    outPath?: string
}

export interface DenoiseAudioOut {
    base64: string
    sampleRate: number
}

export interface DenoiseFileResult {
    outPath: string
    sampleRate: number
    ms: number
}

export interface DenoiseStreamInfo {
    id: number
    sampleRate: number
    frameShiftSamples: number
}

/** 整文件 GTCRN 增强，输出 16k 单声道 WAV */
export function asrDenoiseFile(path: string, config: DenoiseConfig): DenoiseFileResult {
    return JSON.parse(media.asrDenoiseFileJson(path, JSON.stringify(config)))
}

/** 整段 PCM GTCRN 增强：base64 进、base64 出（16k 单声道） */
export function asrDenoisePcm(
    data: string | { base64: string },
    options: AsrPcmOptions = {},
    config?: DenoiseConfig
): DenoiseAudioOut {
    return JSON.parse(media.asrDenoisePcmJson(
        asrBase64(data), options.sampleRate || 0, options.channels || 0, JSON.stringify(config ?? {})
    ))
}

export function asrDenoiseStartStream(config: DenoiseConfig): DenoiseStreamInfo {
    return JSON.parse(media.asrDenoiseStartJson(JSON.stringify(config)))
}

export function asrDenoiseFeed(
    id: number,
    data: string | { base64: string },
    options: AsrPcmOptions = {}
): DenoiseAudioOut {
    return JSON.parse(media.asrDenoiseFeedJson(
        id, asrBase64(data), options.sampleRate || 0, options.channels || 0
    ))
}

export function asrDenoiseFinish(id: number): DenoiseAudioOut {
    return JSON.parse(media.asrDenoiseFinishJson(id))
}

export function asrDenoiseCancel(id: number): boolean {
    return media.asrDenoiseCancel(id)
}

function asrBase64(data: string | { base64: string }): string {
    return typeof data === 'string' ? data : data.base64
}

// ---- 系统 TTS（android.speech.tts）----

export interface TtsConfig {
    /** TTS 引擎包名；缺省用系统默认引擎 */
    engine?: string
    /** 如 zh-CN、en-US */
    language?: string
    /** 音调，默认 1.0 */
    pitch?: number
    /** 语速，默认 1.0 */
    rate?: number
    /** 音量 0~1，默认 1.0 */
    volume?: number
    /** speak/synthesizeToFile 阻塞超时毫秒，默认 speak 60s / 合成 120s */
    timeoutMs?: number
}

export interface TtsEngineInfo {
    name: string
    label: string
}

export interface TtsStatus {
    available: boolean
    engine: string | null
    language: string | null
    engines?: TtsEngineInfo[]
}

export interface TtsSpeakResult {
    ok: boolean
    ms: number
    error?: string
}

export interface TtsFileResult {
    ok: boolean
    path: string
    bytes: number
    ms: number
    error?: string
}

/** 初始化系统 TTS（阻塞至就绪，最长 8s）。同一时刻只保留一个实例，重复 init 会先 shutdown。 */
export function ttsInit(config: TtsConfig = {}): TtsStatus {
    return JSON.parse(media.ttsInitJson(JSON.stringify(config)))
}

/** 阻塞朗读直到播完；config 可按次覆盖 language/pitch/rate/volume/timeoutMs */
export function ttsSpeak(text: string, config?: TtsConfig): TtsSpeakResult {
    return JSON.parse(media.ttsSpeakJson(text, JSON.stringify(config ?? {})))
}

/** 阻塞合成到 WAV 文件（44 字节标准头 + PCM16），相对路径按脚本目录解析 */
export function ttsSynthesizeToFile(text: string, path: string, config?: TtsConfig): TtsFileResult {
    return JSON.parse(media.ttsSynthesizeToFileJson(text, path, JSON.stringify(config ?? {})))
}

export function ttsStop(): boolean {
    return media.ttsStop()
}

export function ttsShutdown(): void {
    media.ttsShutdown()
}

export function ttsStatus(): TtsStatus {
    return JSON.parse(media.ttsStatusJson())
}