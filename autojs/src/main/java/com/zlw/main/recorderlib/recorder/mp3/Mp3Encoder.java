package com.zlw.main.recorderlib.recorder.mp3;

/**
 * JNI bridge to LAME (libmp3lame.so).
 *
 * 原样保留上游包名：libmp3lame.so 里的符号是
 * Java_com_zlw_main_recorderlib_recorder_mp3_Mp3Encoder_*，换包名会导致
 * UnsatisfiedLinkError。
 *
 * Source: https://github.com/zhaolewei/ZlwAudioRecorder (Apache-2.0)
 *
 * @author zhaolewei on 2018/8/2.
 */
public class Mp3Encoder {

    static {
        System.loadLibrary("mp3lame");
    }

    public native static void close();

    public native static int encode(short[] buffer_l, short[] buffer_r, int samples, byte[] mp3buf);

    public native static int flush(byte[] mp3buf);

    public native static void init(int inSampleRate, int outChannel, int outSampleRate, int outBitrate, int quality);

    public static void init(int inSampleRate, int outChannel, int outSampleRate, int outBitrate) {
        init(inSampleRate, outChannel, outSampleRate, outBitrate, 7);
    }
}
