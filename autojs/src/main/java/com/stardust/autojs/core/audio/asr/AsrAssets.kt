package com.stardust.autojs.core.audio.asr

import android.content.Context
import com.stardust.app.GlobalAppContext.get
import java.io.File

/**
 * APK 内置模型的落盘提取：「asset:asr-model/xxx.onnx」→ filesDir/asr-assets/v<版本>/xxx.onnx。
 * sherpa native 只认磁盘路径，assets 里的模型首次使用时拷出来；同版本已存在就复用，
 * 换版本（APK 升级）自动落到新目录，旧目录残留几 MB 无害。
 */
object AsrAssets {
    private const val PREFIX = "asset:"

    fun isAssetPath(path: String): Boolean = path.startsWith(PREFIX)

    fun extract(path: String): String {
        val assetPath = path.removePrefix(PREFIX)
        val ctx: Context = get()
        val dir = File(ctx.filesDir, "asr-assets/v${getVersionCode(ctx)}").apply { mkdirs() }
        val out = File(dir, assetPath.substringAfterLast('/'))
        if (!out.isFile || out.length() == 0L) {
            val tmp = File(dir, out.name + ".tmp")
            ctx.assets.open(assetPath).use { input ->
                tmp.outputStream().use { output ->
                    input.copyTo(output, 64 * 1024)
                }
            }
            if (!tmp.renameTo(out)) {
                out.delete()
                if (!tmp.renameTo(out)) throw IllegalStateException("内置模型提取失败: $assetPath")
            }
        }
        return out.absolutePath
    }

    private fun getVersionCode(ctx: Context): Int = try {
        ctx.packageManager.getPackageInfo(ctx.packageName, 0).let {
            if (android.os.Build.VERSION.SDK_INT >= 28) it.longVersionCode.toInt() else it.versionCode
        }
    } catch (e: Exception) {
        0
    }
}
