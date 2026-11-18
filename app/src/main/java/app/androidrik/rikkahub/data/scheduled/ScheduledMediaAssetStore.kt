package app.androidrik.rikkahub.data.scheduled

import android.content.Context
import app.androidrik.rikkahub.data.files.MediaCreationFiles
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.uuid.Uuid

/**
 * 定时模板素材的锁定仓库：用户在任务编辑页选中的图片**立即复制**进应用私有目录
 * （`filesDir/scheduled_media/<目录>/`），授权里存相对路径。后台运行读这些副本，
 * 相册里的原文件后来被删也不影响到点直跑。
 *
 * 目录名用表单的 id：编辑既有任务时就是任务 id，删除任务可以按授权里的路径把文件清干净；
 * 新建表单是一个随机 id，废弃的表单最多留下几张小图，运行任务时按授权引用读取，不做全局清扫。
 */
class ScheduledMediaAssetStore(private val context: Context) {

    /** 把相册里选中的图片复制进表单目录，返回相对 filesDir 的路径；读不出来的一部分被跳过。 */
    suspend fun importImages(uris: List<Uri>, formDirId: String): List<String> = withContext(Dispatchers.IO) {
        val dir = File(context.filesDir, ASSETS_ROOT).resolve(formDirId)
        uris.mapNotNull { uri ->
            runCatching { MediaCreationFiles.importImage(context, uri, dir) }
                .map { file -> "${ASSETS_ROOT}/${formDirId}/${file.name}" }
                .getOrNull()
        }
    }

    /** 保存授权后清掉目录里没有被引用的文件（被替换或删除的素材）。 */
    suspend fun pruneUnreferenced(formDirId: String, keep: Set<String>) =
        pruneUnreferenced(File(context.filesDir, ASSETS_ROOT), formDirId, keep)

    /** 删除任务时按授权里的引用清理素材文件，目录空了就连目录一起删。 */
    suspend fun deleteAssets(assets: List<ScheduledMediaAsset>) =
        deleteAssetFiles(File(context.filesDir, ASSETS_ROOT), assets)

    companion object {
        const val ASSETS_ROOT = "scheduled_media"

        /** 新建表单的素材目录 id；编辑任务时用任务 id，见类注释。 */
        fun newFormDirId(): String = Uuid.random().toString()
    }
}

/** 保存授权后清掉目录里没有被引用的文件（被替换或删除的素材）。素材根目录由调用方给出（单测用临时目录）。 */
internal suspend fun pruneUnreferenced(root: File, formDirId: String, keep: Set<String>) = withContext(Dispatchers.IO) {
    val dir = root.resolve(formDirId)
    val keptNames = keep.map { path -> path.substringAfterLast('/') }.toSet()
    dir.listFiles { file -> file.isFile && file.name !in keptNames }?.forEach(File::delete)
}

/** 删除任务时按授权里的引用清理素材文件，目录空了就连目录一起删。 */
internal suspend fun deleteAssetFiles(root: File, assets: List<ScheduledMediaAsset>) = withContext(Dispatchers.IO) {
    val dirs = mutableSetOf<File>()
    for (asset in assets) {
        // 授权里的路径经过序列化往返，这里按字符串拦一道，不走出素材根目录
        val name = asset.path.substringAfterLast('/')
        val dirId = asset.path.removePrefix("${ScheduledMediaAssetStore.ASSETS_ROOT}/").substringBefore('/')
        if (dirId.isEmpty() || dirId == ".." || name.isEmpty() || name == ".." || name.contains('/')) continue
        val file = root.resolve(dirId).resolve(name)
        if (file.isFile) {
            file.delete()
            dirs += file.parentFile ?: continue
        }
    }
    dirs.forEach { dir -> if (dir.isDirectory && dir.listFiles { it.isFile }.isNullOrEmpty()) dir.delete() }
}
