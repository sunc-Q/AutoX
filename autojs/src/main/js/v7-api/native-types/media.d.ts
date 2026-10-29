declare namespace media {
    function scanFile(path: string)
    function createMediaPlayer(): NativeMediaPlayer
    function startRecord(optionsJson?: string): string
    function stopRecord(): string
    function getRecordState(): string
    function getRecordLevel(): number
    function readRecordPcmJson(maxBytes: number): string
    function waitRecordSound(timeoutMs: number, thresholdDb: number): boolean
    function isSystemAudioActive(): boolean
    function isRecording(): boolean
    function pauseRecord(): void
    function resumeRecord(): void
    function hasRecordPermission(): boolean
    function requestRecordPermission(): boolean
    function asrStatusJson(): string
    function asrLoadPluginEngine(packageName: string, className: string, configJson: string): string
    function asrUnloadEngine(): void
    function asrTranscribePcmJson(base64: string, sampleRate: number, channels: number): string
    function asrTranscribeFileJson(path: string, sampleRate: number, channels: number): string
    function asrStartStreamJson(): string
    function asrFeedStreamJson(id: number, base64: string, sampleRate: number, channels: number): string
    function asrFinishStreamJson(id: number): string
    function asrCancelStream(id: number): boolean
    function asrLoadBuiltinEngineJson(configJson: string): string
    function asrVadSegmentFileJson(path: string, configJson: string): string
    function asrVadStartStreamJson(configJson: string): string
    function asrVadFeedJson(id: number, base64: string, sampleRate: number, channels: number): string
    function asrVadFinishJson(id: number): string
    function asrVadCancel(id: number): boolean
    function asrDenoiseFileJson(path: string, configJson: string): string
    function asrDenoisePcmJson(base64: string, sampleRate: number, channels: number, configJson: string): string
    function asrDenoiseStartJson(configJson: string): string
    function asrDenoiseFeedJson(id: number, base64: string, sampleRate: number, channels: number): string
    function asrDenoiseFinishJson(id: number): string
    function asrDenoiseCancel(id: number): boolean
    function ttsInitJson(configJson: string): string
    function ttsSpeakJson(text: string, configJson: string): string
    function ttsSynthesizeToFileJson(text: string, path: string, configJson: string): string
    function ttsStop(): boolean
    function ttsShutdown(): void
    function ttsStatusJson(): string
}
