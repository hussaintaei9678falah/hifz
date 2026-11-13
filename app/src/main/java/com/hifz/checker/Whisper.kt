package com.hifz.checker

object Whisper {
    init { System.loadLibrary("hifzwhisper") }
    external fun nativeInit(path: String): Long
    external fun nativeRun(ctx: Long, audio: FloatArray, threads: Int): ByteArray
}
