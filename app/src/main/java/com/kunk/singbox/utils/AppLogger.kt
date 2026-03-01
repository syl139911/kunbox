package com.kunk.singbox.utils

import android.util.Log

/**
 * 搴旂敤鏃ュ織宸ュ叿绫?
 *
 * [乱码注释已清理]
 * - Release 鏋勫缓榛樿鍏抽棴 DEBUG/VERBOSE 绾у埆鏃ュ織
 * [乱码注释已清理]
 * - 鏀寔鍔ㄦ€佽皟鏁存棩蹇楃骇鍒?
 */
object AppLogger {

    /**
     * 鏃ュ織绾у埆
     */
    enum class Level(val priority: Int) {
        VERBOSE(Log.VERBOSE),
        DEBUG(Log.DEBUG),
        INFO(Log.INFO),
        WARN(Log.WARN),
        ERROR(Log.ERROR),
        NONE(Int.MAX_VALUE)
    }

    /**
     * [乱码注释已清理]
     * [乱码注释已清理]
     */
    @Volatile
    var minLevel: Level = Level.INFO

    /**
     * [乱码注释已清理]
     */
    @Volatile
    var enabled: Boolean = true

    @PublishedApi
    internal fun isLoggable(level: Level): Boolean {
        return enabled && level.priority >= minLevel.priority
    }

    /**
     * VERBOSE 绾у埆鏃ュ織
     */
    inline fun v(tag: String, message: () -> String) {
        if (isLoggable(Level.VERBOSE)) {
            Log.v(tag, message())
        }
    }

    /**
     * DEBUG 绾у埆鏃ュ織
     */
    inline fun d(tag: String, message: () -> String) {
        if (isLoggable(Level.DEBUG)) {
            Log.d(tag, message())
        }
    }

    /**
     * INFO 绾у埆鏃ュ織
     */
    inline fun i(tag: String, message: () -> String) {
        if (isLoggable(Level.INFO)) {
            Log.i(tag, message())
        }
    }

    /**
     * WARN 绾у埆鏃ュ織
     */
    inline fun w(tag: String, message: () -> String) {
        if (isLoggable(Level.WARN)) {
            Log.w(tag, message())
        }
    }

    /**
     * [乱码注释已清理]
     */
    inline fun w(tag: String, throwable: Throwable?, message: () -> String) {
        if (isLoggable(Level.WARN)) {
            Log.w(tag, message(), throwable)
        }
    }

    /**
     * ERROR 绾у埆鏃ュ織
     */
    inline fun e(tag: String, message: () -> String) {
        if (isLoggable(Level.ERROR)) {
            Log.e(tag, message())
        }
    }

    /**
     * [乱码注释已清理]
     */
    inline fun e(tag: String, throwable: Throwable?, message: () -> String) {
        if (isLoggable(Level.ERROR)) {
            Log.e(tag, message(), throwable)
        }
    }

    /**
     * [乱码注释已清理]
     */
    fun v(tag: String, message: String) {
        if (isLoggable(Level.VERBOSE)) Log.v(tag, message)
    }

    fun d(tag: String, message: String) {
        if (isLoggable(Level.DEBUG)) Log.d(tag, message)
    }

    fun i(tag: String, message: String) {
        if (isLoggable(Level.INFO)) Log.i(tag, message)
    }

    fun w(tag: String, message: String) {
        if (isLoggable(Level.WARN)) Log.w(tag, message)
    }

    fun w(tag: String, message: String, throwable: Throwable?) {
        if (isLoggable(Level.WARN)) Log.w(tag, message, throwable)
    }

    fun e(tag: String, message: String) {
        if (isLoggable(Level.ERROR)) Log.e(tag, message)
    }

    fun e(tag: String, message: String, throwable: Throwable?) {
        if (isLoggable(Level.ERROR)) Log.e(tag, message, throwable)
    }
}
