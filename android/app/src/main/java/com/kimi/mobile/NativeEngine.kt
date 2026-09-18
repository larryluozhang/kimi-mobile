package com.kimi.mobile

import android.content.Context
import android.util.Log
import java.io.File

/**
 * sherpa-onnx 原生引擎加载器：4 个 .so（约 30MB）不打进 APK，按需下载到
 * filesDir/native-engine/1.13.7/（AAR 只保留 Java 类）。
 *
 * AAR 里 OnlineStream 的静态初始化会 System.loadLibrary("sherpa-onnx-jni")，
 * 只搜应用安装 lib 目录，所以这里先反射把下载目录注入 classloader 的
 * nativeLibraryDirectories，再按依赖顺序用绝对路径 System.load 四个库兜底。
 */
object NativeEngine {

    private const val TAG = "NativeEngine"
    private const val ENGINE_DIR = "native-engine/1.13.7"

    /** 按依赖加载顺序排列：onnxruntime → c-api → cxx-api → jni */
    val ENGINE_FILES = listOf(
        "libonnxruntime.so",
        "libsherpa-onnx-c-api.so",
        "libsherpa-onnx-cxx-api.so",
        "libsherpa-onnx-jni.so",
    )

    @Volatile private var loaded = false

    /** 引擎运行时目录（filesDir/native-engine/1.13.7） */
    fun engineDir(context: Context): File = File(context.filesDir, ENGINE_DIR)

    /** 引擎 4 件套是否已下载就位（未下载返回 false，调用方回退系统识别或提示去设置页下载） */
    fun isEngineAvailable(context: Context): Boolean {
        val dir = engineDir(context)
        return ENGINE_FILES.all { File(dir, it).isFile }
    }

    /** 加载原生引擎（幂等，线程安全）。成功 true；任何失败记日志返回 false，不抛异常。 */
    @Synchronized
    fun load(context: Context): Boolean {
        if (loaded) return true
        val dir = engineDir(context)
        if (!isEngineAvailable(context)) {
            Log.w(TAG, "engine files missing in $dir")
            return false
        }
        // (a) 反射把下载目录加入 classloader 的 native 库搜索路径（供 loadLibrary 兜底）；
        //     失败不中止——(b) 的绝对路径加载通常已足够
        try {
            addNativeLibraryDir(dir)
        } catch (t: Throwable) {
            Log.w(TAG, "inject nativeLibraryDirectories failed, continue with System.load", t)
        }
        // (b) 按依赖顺序绝对路径加载 4 个库
        return try {
            for (name in ENGINE_FILES) {
                System.load(File(dir, name).absolutePath)
            }
            loaded = true
            Log.i(TAG, "native engine loaded from $dir")
            true
        } catch (t: Throwable) {
            Log.e(TAG, "load native engine failed", t)
            false
        }
    }

    /** BaseDexClassLoader.pathList → DexPathList.nativeLibraryDirectories，add(0, dir) */
    @Throws(Throwable::class)
    private fun addNativeLibraryDir(dir: File) {
        val classLoader = NativeEngine::class.java.classLoader!!
        val pathList = findField(classLoader, "pathList").get(classLoader)!!
        @Suppress("UNCHECKED_CAST")
        val dirs = findField(pathList, "nativeLibraryDirectories").get(pathList) as ArrayList<File>
        if (!dirs.contains(dir)) dirs.add(0, dir)
    }

    private fun findField(instance: Any, name: String): java.lang.reflect.Field {
        var cls: Class<*>? = instance.javaClass
        while (cls != null) {
            try {
                return cls.getDeclaredField(name).apply { isAccessible = true }
            } catch (e: NoSuchFieldException) {
                cls = cls.superclass
            }
        }
        throw NoSuchFieldException(name)
    }
}
