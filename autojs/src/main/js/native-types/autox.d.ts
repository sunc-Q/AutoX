
namespace Autox {
    interface ShellResult {
        code: number
        result: string
        error: string
    }
    interface Shizuku {
        isShizukuAlive(): boolean
        runRhinoScriptFile(path: string): string
        runRhinoScript(script: string): string
        runShizukuShellCommand(cmd: string): ShellResult
        openAccessibility(): void
    }
    interface PFileInterface {
        getPath(): string
    }
    interface Shell2 {
        exec(cmd: string): void
        execAndWaitFor(cmd: string): ShellResult
        exit(): void
        exitAndWaitFor(): void
        setCallback(callback: {
            onOutput: (out: string) => void
            onNewLine: (line: string) => void
        }): void
    }
    interface Shell {
        exec(cmd: string, root: boolean): ShellResult
        createShell(root: boolean): Shell2
    }
    interface Files {
        path(relativePath: string): string
        cwd(): string
        readAssets(path: string, encoding?: string): string
        join(parent: string, ...child: string[]): string
        read(path: string): string
        open(path: string, mode?: string, encoding?: string, bufferSize?: number): PFileInterface
        create(path: string): boolean
        createIfNotExists(path: string): boolean
        createWithDirs(path: string): boolean
        exists(path: string): boolean
        ensureDir(path: string): boolean
        read(path: string, encoding?: string): string
        readBytes(path: string): any
        write(path: string, text: string, encoding: string): void
        append(path: string, text: string, encoding?: string): void
        appendBytes(path: string, bytes: any): void
        writeBytes(path: string, bytes: any): string
        copy(pathFrom: string, pathTo: string): boolean
        renameWithoutExtension(path: string, newName: string): boolean
        rename(path: string, newName: string): boolean
        move(path: string, newPath: string): boolean
        getExtension(fileName: string): string | null
        getName(filePath: string): string
        getNameWithoutExtension(filePath: string): string
        remove(path: string): boolean
        removeDir(path: string): boolean
        getSdcardPath(): string
        listDir(path: string, filter?: (name: string) => boolean): string[]
        isFile(path: string): boolean
        isDir(path: string): boolean
        isEmptyDir(path: string): boolean
        join(parent: string, ...child: string[]): string
        getHumanReadableSize(bytes: number | bigint): string
        getSimplifiedPath(path: string): string
    }

    interface Media {
        scanFile(path: string): void
        playMusic(path: string, volume?: number, looping?: boolean): void
        musicSeekTo(m: number): void
        isMusicPlaying(): boolean
        pauseMusic(): void
        resumeMusic(): void
        getMusicDuration(): number
        getMusicCurrentPosition(): number
        stopMusic(): void
        /** 录音/内录封装 */
        record: MediaRecord
        /** 原始接口：参数为 JSON 字符串，返回 JSON 字符串 */
        startRecord(optionsJson?: string): string
        stopRecord(): string
        pauseRecord(): void
        resumeRecord(): void
        isRecording(): boolean
        getRecordState(): string
        getRecordLevel(): number
        /** 原始接口：返回 Java 对象（base64 + short[]），无数据为 null */
        readRecordPcm(maxBytes: number): AudioRecordPcm | null
        waitRecordSound(timeoutMs: number, thresholdDb: number): boolean
        isSystemAudioActive(): boolean
        hasRecordPermission(): boolean
        requestRecordPermission(): boolean
        /** 语音转文字（接口已预留，需先加载 ASR 引擎） */
        asr: AsrApi
        tts: TtsApi
        /** 原始接口：全部为 JSON 字符串 */
        asrStatusJson(): string
        asrLoadPluginEngine(packageName: string, className: string, configJson: string): string
        asrUnloadEngine(): void
        asrTranscribePcmJson(base64: string, sampleRate: number, channels: number): string
        asrTranscribeFileJson(path: string, sampleRate: number, channels: number): string
        asrStartStreamJson(): string
        asrFeedStreamJson(id: number, base64: string, sampleRate: number, channels: number): string
        asrFinishStreamJson(id: number): string
        asrCancelStream(id: number): boolean
        asrLoadBuiltinEngineJson(configJson: string): string
        asrVadSegmentFileJson(path: string, configJson: string): string
        asrVadStartStreamJson(configJson: string): string
        asrVadFeedJson(id: number, base64: string, sampleRate: number, channels: number): string
        asrVadFinishJson(id: number): string
        asrVadCancel(id: number): boolean
        asrDenoiseFileJson(path: string, configJson: string): string
        asrDenoisePcmJson(base64: string, sampleRate: number, channels: number, configJson: string): string
        asrDenoiseStartJson(configJson: string): string
        asrDenoiseFeedJson(id: number, base64: string, sampleRate: number, channels: number): string
        asrDenoiseFinishJson(id: number): string
        asrDenoiseCancel(id: number): boolean
        ttsInitJson(configJson: string): string
        ttsSpeakJson(text: string, configJson: string): string
        ttsSynthesizeToFileJson(text: string, path: string, configJson: string): string
        ttsStop(): boolean
        ttsShutdown(): void
        ttsStatusJson(): string
    }

    interface MediaRecord {
        start(options?: AudioRecordOptions): AudioRecordResult
        stop(): AudioRecordResult & { state: string }
        pause(): void
        resume(): void
        isRecording(): boolean
        state(): AudioRecordState
        level(): number
        /** 拉一段 PCM 波形（base64 + samples），无数据为 null */
        readPcm(maxBytes?: number): AudioRecordPcm | null
        /** 只取采样点，便于 Math.max / JSON.stringify 存文本 */
        readSamples(maxBytes?: number): number[] | null
        /** 阻塞等待出现声音，超时返回 false。不要在 UI 线程调用 */
        waitForSound(timeoutMs?: number, thresholdDb?: number): boolean
        /** 系统当前是否有 App 在出声，不需要录音授权 */
        isSystemAudioActive(): boolean
        hasPermission(): boolean
        ensurePermission(): boolean
    }

    interface AudioRecordPcm {
        /** PCM16LE 的 Base64，可直接 WebSocket 外送 */
        base64: string
        /** 采样点（PCM16 有符号数），便于分析 */
        samples: number[]
        bytes: number
        sampleRate: number
        channels: number
        /** 环形缓冲累计写入字节数，可用于对齐/去重 */
        seq: number
        /** 因消费太慢被丢弃的字节数 */
        droppedBytes: number
    }

    interface AudioRecordOptions {
        /** 录音来源：mic=麦克风（默认），system=系统内录（Android 10+） */
        source?: 'mic' | 'system'
        /** 输出格式，默认 wav；也可由 path 后缀推断。none=不落盘，只做波形/静音分析 */
        format?: 'wav' | 'pcm' | 'mp3' | 'none'
        /** 输出路径；相对路径落在 /sdcard 下，省略则自动生成 record_时间戳 */
        path?: string
        /** 采样率，默认麦克风 16000、内录 44100 */
        sampleRate?: number
        /** 声道数，默认麦克风 1、内录 2 */
        channels?: 1 | 2
        /** MP3 码率（kbps），默认 128 */
        bitRate?: number
        /** 内录匹配的音频用途，默认 ['media','game']，也可写数值 */
        usages?: (number | 'unknown' | 'media' | 'game' | 'voice_communication' | 'alarm' | 'notification' | 'notification_ringtone' | 'assistant')[]
        /** 波形环形缓冲大小（字节），默认 2MB，0~64MB；太小会丢数据 */
        tapBufferBytes?: number
        /** 判定「有声音」的阈值（dBFS），默认 -50 */
        silenceThresholdDb?: number
    }

    interface AudioRecordResult {
        state?: string
        path: string
        source: 'mic' | 'system'
        format: 'wav' | 'pcm' | 'mp3' | 'none'
        sampleRate: number
        channels: number
        bytes: number
        durationMs: number
        peakDb: number
        droppedBytes: number
        error?: string
    }

    interface AudioRecordState {
        state: 'idle' | 'recording' | 'paused'
        path?: string
        source?: string
        format?: string
        sampleRate?: number
        channels?: number
        bytes?: number
        durationMs?: number
        elapsedMs?: number
        level?: number
        /** 采集以来的峰值电平（dBFS） */
        peakDb?: number
        /** 最近 1 秒内是否有声音 */
        soundDetected?: boolean
        /** 距上次检测到声音过了多久；从没检测到则从开始录音算起 */
        silentMs?: number
        /** 累计「有声音」的毫秒数 */
        soundMs?: number
        /** 当前判定阈值（dBFS） */
        thresholdDb?: number
        /** 波形环形缓冲里还未被读走的字节数 */
        bufferedBytes?: number
        /** 因消费太慢被丢弃的字节数 */
        droppedBytes?: number
        last?: AudioRecordResult
        error?: string
    }

    interface AsrStatus {
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

    interface AsrPcmOptions {
        /** 输入 PCM 的采样率（录音默认麦克风 16000、内录 44100） */
        sampleRate?: number
        channels?: 1 | 2
    }

    interface AsrSegment {
        text: string
        startMs: number
        endMs: number
    }

    interface AsrResult {
        text: string
        segments: AsrSegment[]
        isFinal: boolean
        sampleRate: number
    }

    interface AsrStreamInfo {
        id: number
        sampleRate: number
        streaming: boolean
    }

    interface AsrSnapshot {
        id: number
        /** 已定稿段 + 当前实时假设 */
        text: string
        partial: string
        segments: AsrSegment[]
        isFinal: boolean
        /** 已送入引擎的音频时长 */
        fedMs: number
    }

    interface AsrApi {
        status(): AsrStatus
        loadPluginEngine(packageName: string, className: string, config?: any): AsrStatus
        /** 注册内置 sherpa-onnx 引擎（zipformer/paraformer/sensevoice），模型路径在配置里给 */
        loadBuiltin(config?: any): AsrStatus
        unload(): void
        transcribePcm(data: string | { base64: string }, options?: AsrPcmOptions): AsrResult
        transcribeFile(path: string, options?: AsrPcmOptions): AsrResult
        startStream(): AsrStreamInfo
        feed(id: number, data: string | { base64: string }, options?: AsrPcmOptions): AsrSnapshot
        finish(id: number): AsrSnapshot
        cancel(id: number): boolean
        /** 文件整段按 VAD 切段 */
        vadSegmentFile(path: string, config: any): Autox.VadFileResult
        vadStartStream(config: any): Autox.VadStreamInfo
        vadFeed(id: number, data: string | { base64: string }, options?: AsrPcmOptions): Autox.VadFeedResult
        vadFinish(id: number): Autox.VadFeedResult
        vadCancel(id: number): boolean
        /** GTCRN 语音增强 */
        denoiseFile(path: string, config: any): Autox.DenoiseFileResult
        denoisePcm(data: string | { base64: string }, options?: AsrPcmOptions, config?: any): Autox.DenoiseAudioOut
        denoiseStartStream(config: any): Autox.DenoiseStreamInfo
        denoiseFeed(id: number, data: string | { base64: string }, options?: AsrPcmOptions): Autox.DenoiseAudioOut
        denoiseFinish(id: number): Autox.DenoiseAudioOut
        denoiseCancel(id: number): boolean
    }

    interface VadSegment {
        startMs: number
        endMs: number
        ms?: number
        base64?: string
    }

    interface VadFileResult {
        sampleRate: number
        segments: VadSegment[]
    }

    interface VadStreamInfo {
        id: number
        sampleRate: number
    }

    interface VadFeedResult {
        speech: boolean
        segments: VadSegment[]
    }

    interface DenoiseAudioOut {
        base64: string
        sampleRate: number
    }

    interface DenoiseFileResult {
        outPath: string
        sampleRate: number
        ms: number
    }

    interface DenoiseStreamInfo {
        id: number
        sampleRate: number
        frameShiftSamples: number
    }

    interface TtsConfig {
        engine?: string
        language?: string
        pitch?: number
        rate?: number
        volume?: number
        timeoutMs?: number
    }

    interface TtsEngineInfo {
        name: string
        label: string
    }

    interface TtsStatus {
        available: boolean
        engine: string | null
        language: string | null
        engines?: TtsEngineInfo[]
    }

    interface TtsSpeakResult {
        ok: boolean
        ms: number
        error?: string
    }

    interface TtsFileResult {
        ok: boolean
        path: string
        bytes: number
        ms: number
        error?: string
    }

    interface TtsApi {
        init(config?: TtsConfig): TtsStatus
        speak(text: string, config?: TtsConfig): TtsSpeakResult
        synthesizeToFile(text: string, path: string, config?: TtsConfig): TtsFileResult
        stop(): boolean
        shutdown(): void
        status(): TtsStatus
    }

    interface Ui {
        readonly layoutInflater: any
        bindingContext: any
        __proxy__: any
    }
    interface Threads {
        currentThread(): any
        start(runnable: Runnable): TimerThread
        exit(): void
        shutDownAll(): void
        lock(): any
        disposable(): any
        runTaskForThreadPool(runnable: BaseFunction): void
        atomic(value: number | bigint): any
        hasRunningThreads(): boolean
    }
    interface Image {
        mat: any
        height: number
        width: number
    }
    interface AppUtils {
        fileProviderAuthority: string | null
        launchPackage(packageName: string): boolean
        sendLocalBroadcastSync(intent: android.Intent)
        launchApp(appName: string): boolean
        getPackageName(appName: string): string | null
        getInstalledPackages(flags: number): Array<PackageInfo>
        getAppName(packageName: string): string | null
        uninstall(packageName: string)
        viewFile(path: string)
        editFile(path: string)
        getUriForFile(path: string): android.Uri
        openUrl(url: string)
        openAppSetting(packageName: string): boolean
    }
    interface ScriptExecution {
        getEngine(): ScriptEngine
        getConfig(): any
    }
    interface ScriptEngine {
        isDestroyed(): boolean
        forceStop()
        cwd(): string | null
        getSource(): ScriptSource
        emit(eventName: string, ...args: any[]): void
    }
    interface Engines {
        [key: string]: any
        stopAll(): void
        stopAllAndToast(): void
        myEngine(): ScriptEngine
        all(): ScriptEngine[]
        execScript(name: string, script: string, config?: EngineConfig): ScriptExecution
        execScriptFile(path: string, config?: EngineConfig): ScriptExecution
        execAutoFile(path: string, config?: EngineConfig): ScriptExecution
    }
    interface Runtime {
        loopers: any
        shizuku: Shizuku
        shell: Shell
        files: Files
        media: Media
        ui: Ui
        threads: Threads
        floaty: any
        colors: any
        images: any
        bridges: any
        automator: Automator
        accessibilityBridge: any
        app: AppUtils
        engines: Engines
        dialogs: any
        events: Events
        plugins: any
        sensors: any
        termux: any
        getScreenMetrics(): any
        evalInContext(script: string, context: Object): any
        getUiHandler: () => any
        selector(): UiSelector
        getProperty(name: string): any
    }

    interface JsBridge {
        registerHandler: (name: string,
            handler: (data: string | null,
                callBack?: (data?: string) => void) => void) => void
        callHandler: (name: string, data?: string,
            callBack?: (data: string | null) => void) => void
    }
}