package com.stardust.autojs.runtime.api;

import android.content.Context;
import android.media.MediaPlayer;
import android.media.MediaScannerConnection;
import android.net.Uri;

import com.stardust.autojs.core.audio.AudioRecordManager;
import com.stardust.autojs.core.audio.PcmChunk;
import com.stardust.autojs.core.audio.TtsManager;
import com.stardust.autojs.core.audio.asr.AsrDenoiseManager;
import com.stardust.autojs.core.audio.asr.AsrManager;
import com.stardust.autojs.core.audio.asr.AsrVadManager;
import com.stardust.autojs.core.image.capture.ScreenCaptureRequester;
import com.stardust.autojs.runtime.ScriptRuntime;
import com.stardust.pio.UncheckedIOException;
import com.stardust.util.MimeTypes;

import java.io.IOException;

/**
 * Created by Stardust on 2018/2/12.
 */

public class Media implements MediaScannerConnection.MediaScannerConnectionClient {

    private MediaScannerConnection mScannerConnection;
    private MediaPlayerWrapper mMediaPlayer;
    private ScriptRuntime mRuntime;
    private final Context mContext;
    private final ScreenCaptureRequester mScreenCaptureRequester;

    public Media(Context context, ScriptRuntime runtime, ScreenCaptureRequester screenCaptureRequester) {
        mScannerConnection = new MediaScannerConnection(context, this);
        mContext = context;
        mRuntime = runtime;
        mScreenCaptureRequester = screenCaptureRequester;
        mScannerConnection.connect();
    }

    public void scanFile(String path) {
        String mimeType = MimeTypes.fromFileOr(path, null);
        mScannerConnection.scanFile(mRuntime.files.path(path), mimeType);
    }

    @Override
    public void onMediaScannerConnected() {

    }

    public void playMusic(String path, float volume) {
        playMusic(path, volume, false);
    }

    public void playMusic(String path) {
        playMusic(path, 1.0f);
    }

    public void playMusic(String path, float volume, boolean looping) {
        path = mRuntime.files.path(path);
        if (mMediaPlayer == null) {
            mMediaPlayer = new MediaPlayerWrapper();
        }
        mMediaPlayer.stopAndReset();
        try {
            mMediaPlayer.setDataSource(path);
            mMediaPlayer.setVolume(volume, volume);
            mMediaPlayer.setLooping(looping);
            mMediaPlayer.prepare();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        mMediaPlayer.start();
    }

    public void musicSeekTo(int m) {
        if (mMediaPlayer == null)
            return;
        mMediaPlayer.seekTo(m);
    }

    public boolean isMusicPlaying() {
        return mMediaPlayer != null && mMediaPlayer.isPlaying();
    }

    public void pauseMusic() {
        if (mMediaPlayer == null)
            return;
        mMediaPlayer.pause();
    }

    public void resumeMusic() {
        if (mMediaPlayer == null)
            return;
        mMediaPlayer.start();
    }

    public int getMusicDuration() {
        if (mMediaPlayer == null) {
            return 0;
        }
        return mMediaPlayer.getDuration();
    }

    public int getMusicCurrentPosition() {
        if (mMediaPlayer == null)
            return -1;
        return mMediaPlayer.getCurrentPosition();
    }

    public void stopMusic() {
        if (mMediaPlayer == null)
            return;
        mMediaPlayer.stop();
    }


    @Override
    public void onScanCompleted(String path, Uri uri) {

    }

    /**
     * 开始录音。参数为 JSON 字符串，详见 {@link AudioRecordManager#startJson}。
     * 返回录音会话信息（JSON）。录音跑在进程级单例里，脚本结束后仍会继续。
     */
    public String startRecord(String optionsJson) {
        return AudioRecordManager.startJson(mContext, mScreenCaptureRequester, optionsJson);
    }

    /**
     * 停止录音（任何脚本都可以停，方便跨脚本收尾），返回录音结果（JSON）。
     */
    public String stopRecord() {
        return AudioRecordManager.stopJson();
    }

    public void pauseRecord() {
        AudioRecordManager.pause();
    }

    public void resumeRecord() {
        AudioRecordManager.resume();
    }

    public boolean isRecording() {
        return AudioRecordManager.isRecording();
    }

    /**
     * 当前录音状态（JSON）：state / path / durationMs / level 等；空闲时带 last。
     */
    public String getRecordState() {
        return AudioRecordManager.getStateJson();
    }

    /**
     * 最近一次采集缓冲的 RMS 电平（dBFS，-120 表示无声/无数据）。
     */
    public double getRecordLevel() {
        return AudioRecordManager.getLevel();
    }

    /**
     * 拉取一段 PCM 波形数据（PCM16LE，录音原始采样率/声道）。
     * 返回对象同时带 base64（便于 WebSocket 外送）和 samples（short[]，便于分析），
     * 没有可读数据时返回 null。录音停止后仍可继续拉取缓冲区剩余部分。
     */
    public PcmChunk readRecordPcm(int maxBytes) {
        return AudioRecordManager.readPcm(maxBytes);
    }

    /**
     * 阻塞等待出现声音，level 达到 thresholdDb（dBFS）即返回 true，超时返回 false。
     * 不要在 UI 线程调用。
     */
    public boolean waitRecordSound(long timeoutMs, double thresholdDb) {
        return AudioRecordManager.waitForSound(timeoutMs, thresholdDb);
    }

    /**
     * 系统当前是否有 App 在播放声音（AudioManager.isMusicActive）。
     * 不依赖录音授权，可以单独用来判断「系统有没有在出声」。
     */
    public boolean isSystemAudioActive() {
        return AudioRecordManager.isSystemAudioActive(mContext);
    }

    public boolean hasRecordPermission() {
        return AudioRecordManager.hasRecordPermission(mContext);
    }

    /**
     * 申请录音权限并阻塞等待结果（最长 15 秒），返回是否授权。不要在主线程调用。
     */
    public boolean requestRecordPermission() {
        return AudioRecordManager.requestRecordPermission(mContext, 15000L);
    }

    // ---- 语音转文字：接口已预留，引擎由 AsrManager 注册 ----

    public String asrStatusJson() {
        return AsrManager.statusJson();
    }

    public String asrLoadPluginEngine(String packageName, String className, String configJson) {
        return AsrManager.loadPluginEngine(mContext, packageName, className, configJson);
    }

    public void asrUnloadEngine() {
        AsrManager.unloadEngine();
    }

    /** PCM16LE 的 Base64（readRecordPcm 返回的 base64 可直接用）。 */
    public String asrTranscribePcmJson(String base64, int sampleRate, int channels) {
        return AsrManager.transcribePcmJson(base64, sampleRate, channels);
    }

    /** WAV 自动识别采样率/声道；裸 PCM 需传 sampleRate。 */
    public String asrTranscribeFileJson(String path, int sampleRate, int channels) {
        return AsrManager.transcribeFileJson(mRuntime.files.path(path), sampleRate, channels);
    }

    public String asrStartStreamJson() {
        return AsrManager.startStreamJson();
    }

    public String asrFeedStreamJson(int id, String base64, int sampleRate, int channels) {
        return AsrManager.feedStreamJson(id, base64, sampleRate, channels);
    }

    public String asrFinishStreamJson(int id) {
        return AsrManager.finishStreamJson(id);
    }

    public boolean asrCancelStream(int id) {
        return AsrManager.cancelStream(id);
    }

    /** 注册内置 sherpa-onnx 引擎（zipformer/paraformer/sensevoice），模型路径在配置里给。 */
    public String asrLoadBuiltinEngineJson(String configJson) {
        return AsrManager.loadBuiltinEngine(configJson);
    }

    // ---- VAD 切段（Silero） ----

    public String asrVadSegmentFileJson(String path, String configJson) {
        return AsrVadManager.segmentFileJson(mRuntime.files.path(path), configJson);
    }

    public String asrVadStartStreamJson(String configJson) {
        return AsrVadManager.startJson(configJson);
    }

    public String asrVadFeedJson(int id, String base64, int sampleRate, int channels) {
        return AsrVadManager.feedJson(id, base64, sampleRate, channels);
    }

    public String asrVadFinishJson(int id) {
        return AsrVadManager.finishJson(id);
    }

    public boolean asrVadCancel(int id) {
        return AsrVadManager.cancel(id);
    }

    // ---- GTCRN 语音增强 ----

    public String asrDenoiseFileJson(String path, String configJson) {
        return AsrDenoiseManager.fileJson(mRuntime.files.path(path), configJson);
    }

    public String asrDenoisePcmJson(String base64, int sampleRate, int channels, String configJson) {
        return AsrDenoiseManager.pcmJson(base64, sampleRate, channels, configJson);
    }

    public String asrDenoiseStartJson(String configJson) {
        return AsrDenoiseManager.startJson(configJson);
    }

    public String asrDenoiseFeedJson(int id, String base64, int sampleRate, int channels) {
        return AsrDenoiseManager.feedJson(id, base64, sampleRate, channels);
    }

    public String asrDenoiseFinishJson(int id) {
        return AsrDenoiseManager.finishJson(id);
    }

    public boolean asrDenoiseCancel(int id) {
        return AsrDenoiseManager.cancel(id);
    }

    // ---- 系统 TTS（android.speech.tts）----

    public String ttsInitJson(String configJson) {
        return TtsManager.initJson(mContext, configJson);
    }

    /** 阻塞朗读直到播完（或 config.timeoutMs 超时）。 */
    public String ttsSpeakJson(String text, String configJson) {
        return TtsManager.speakJson(text, configJson);
    }

    /** 阻塞合成到 WAV 文件；相对路径按脚本目录解析。 */
    public String ttsSynthesizeToFileJson(String text, String path, String configJson) {
        return TtsManager.synthesizeToFileJson(text, mRuntime.files.path(path), configJson);
    }

    public boolean ttsStop() {
        return TtsManager.stop();
    }

    public void ttsShutdown() {
        TtsManager.shutdown();
    }

    public String ttsStatusJson() {
        return TtsManager.statusJson();
    }

    public void recycle() {
        if (mScannerConnection != null) {
            mScannerConnection.disconnect();
            mScannerConnection = null;
        }
        if (mMediaPlayer != null) {
            mMediaPlayer.release();
        }
    }

    private static class MediaPlayerWrapper extends MediaPlayer {

        static final int STATE_NOT_INITIALIZED = 0;
        static final int STATE_PREPARING = 1;
        static final int STATE_PREPARED = 2;
        static final int STATE_START = 3;
        static final int STATE_PAUSED = 4;
        static final int STATE_STOPPED = 5;
        static final int STATE_RELEASED = 6;

        private int mState = STATE_NOT_INITIALIZED;

        public int getState() {
            return mState;
        }

        @Override
        public void prepare() throws IOException, IllegalStateException {
            mState = STATE_PREPARING;
            super.prepare();
            mState = STATE_PREPARED;
        }

        @Override
        public void prepareAsync() throws IllegalStateException {
            throw new UnsupportedOperationException();
        }

        @Override
        public void start() throws IllegalStateException {
            super.start();
            mState = STATE_START;
        }

        @Override
        public void stop() throws IllegalStateException {
            super.stop();
            mState = STATE_STOPPED;
        }

        @Override
        public void pause() throws IllegalStateException {
            super.pause();
            mState = STATE_PAUSED;
        }

        @Override
        public void release() {
            super.release();
            mState = STATE_RELEASED;
        }

        @Override
        public void reset() {
            super.reset();
            mState = STATE_NOT_INITIALIZED;
        }

        public void stopAndReset() {
            try {
                if (mState == STATE_START || mState == STATE_PAUSED) {
                    stop();
                }
                if (mState != STATE_NOT_INITIALIZED) {
                    reset();
                }
            } catch (IllegalStateException ignored) {

            }
        }
    }
}
