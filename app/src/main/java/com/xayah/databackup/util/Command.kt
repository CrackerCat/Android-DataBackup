package com.xayah.databackup.util

import android.app.usage.StorageStatsManager
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.net.Uri
import android.os.Build
import android.provider.Telephony
import androidx.compose.runtime.mutableStateOf
import com.topjohnwu.superuser.Shell
import com.xayah.databackup.App
import com.xayah.databackup.data.*
import com.xayah.librootservice.RootService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.lingala.zip4j.ZipFile
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.*

class Command {
    companion object {
        const val TAG = "Command"

        private val storageStatsManager =
            App.globalContext.getSystemService(Context.STORAGE_STATS_SERVICE) as StorageStatsManager

        /**
         * 切换至IO协程运行
         */
        private suspend fun <T> runOnIO(block: suspend () -> T): T {
            return withContext(Dispatchers.IO) { block() }
        }

        /**
         * `ls -i`命令
         */
        suspend fun ls(path: String): Boolean {
            execute("ls -i \"${path}\"").apply {
                return this.isSuccess
            }
        }

        /**
         * 利用`ls`计数
         */
        suspend fun countFile(path: String): Int {
            execute("ls -i \"${path}\"").apply {
                return if (this.isSuccess)
                    this.out.size
                else
                    0
            }
        }

        /**
         * `rm -rf`命令, 用于删除文件, 可递归
         */
        suspend fun rm(path: String): Boolean {
            execute("rm -rf \"${path}\"").apply {
                return this.isSuccess
            }
        }

        /**
         * `mkdir`命令, 用于文件夹创建, 可递归
         */
        suspend fun mkdir(path: String): Boolean {
            if (execute("ls -i \"${path}\"").isSuccess)
                return true
            execute("mkdir -p \"${path}\"").apply {
                return this.isSuccess
            }
        }

        /**
         * Deprecated, `unzip`命令, 用于解压zip, 不兼容部分机型
         */
        @Deprecated("unzip", ReplaceWith(""))
        suspend fun unzip(filePath: String, outPath: String) {
            if (mkdir(outPath)) execute("unzip \"${filePath}\" -d \"${outPath}\"")
        }

        /**
         * `cp`命令, 用于复制
         */
        suspend fun cp(src: String, dst: String): Boolean {
            return execute("cp \"${src}\" \"${dst}\"").isSuccess
        }

        /**
         * 使用`net.lingala.zip4j`库解压zip
         */
        fun unzipByZip4j(filePath: String, outPath: String) {
            try {
                ZipFile(filePath).extractAll(outPath)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        /**
         * 构建应用备份哈希表
         */
        suspend fun getAppInfoBackupMap(onProgress: (Float) -> Unit = {}): AppInfoBackupMap {
            var appInfoBackupMap: AppInfoBackupMap = hashMapOf()

            runOnIO {
                // 读取配置文件
                appInfoBackupMap = GsonUtil.getInstance().fromAppInfoBackupMapJson(
                    RootService.getInstance().readText(Path.getAppInfoBackupMapPath())
                )
                val blackListMap = readBlackListMap(App.globalContext.readBlackListMapPath())

                // 根据本机应用调整列表
                val packageManager = App.globalContext.packageManager
                val userId = App.globalContext.readBackupUser()
                // 通过PackageManager获取所有应用信息
                RootService.getInstance().offerInstalledPackagesAsUser(0, userId.toInt())
                val packages = mutableListOf<PackageInfo>()
                var tmp: MutableList<PackageInfo>
                while (
                    run {
                        tmp =
                            RootService.getInstance().pollInstalledPackages()
                        tmp.isNotEmpty()
                    }
                ) {
                    packages.addAll(tmp)
                }
                val packagesSize = packages.size
                for ((index, i) in packages.withIndex()) {
                    try {
                        // 自身或非指定用户应用
                        if (i.packageName == App.globalContext.packageName) continue
                        val isSystemApp =
                            (i.applicationInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0

                        val appIcon = i.applicationInfo.loadIcon(packageManager)
                        val appName = i.applicationInfo.loadLabel(packageManager).toString()
                        val versionName = i.versionName
                        val versionCode = i.longVersionCode
                        val packageName = i.packageName
                        val firstInstallTime = i.firstInstallTime

                        if (appInfoBackupMap.containsKey(packageName).not()) {
                            appInfoBackupMap[packageName] = AppInfoBackup()
                        }
                        val appInfoBackup = appInfoBackupMap[packageName]!!
                        appInfoBackup.apply {
                            this.detailBase.appIcon = appIcon
                            this.detailBase.appName = appName
                            this.detailBase.packageName = packageName
                            this.firstInstallTime = firstInstallTime
                            this.detailBackup.versionName = versionName
                            this.detailBackup.versionCode = versionCode
                            this.detailBase.isSystemApp = isSystemApp
                            this.isOnThisDevice = true
                        }
                        try {
                            RootService.getInstance().queryStatsForPackage(
                                i,
                                RootService.getInstance().getUserHandle(userId.toInt())
                            ).apply {
                                val storageStats =
                                    AppInfoStorageStats(appBytes, cacheBytes, dataBytes)
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                                    storageStats.externalCacheBytes = externalCacheBytes
                                }
                                appInfoBackup.storageStats = storageStats
                            }
                        } catch (e: Exception) {
                            e.printStackTrace()
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                    onProgress((index + 1).toFloat() / packagesSize)
                }

                // 黑名单应用
                for (i in blackListMap.keys) {
                    appInfoBackupMap.remove(i)
                }
            }
            return appInfoBackupMap
        }

        /**
         * 仅读取应用备份哈希表
         */
        suspend fun readAppInfoBackupMap(): AppInfoBackupMap {
            var appInfoBackupMap: AppInfoBackupMap = hashMapOf()

            runOnIO {
                // 读取配置文件
                appInfoBackupMap = GsonUtil.getInstance().fromAppInfoBackupMapJson(
                    RootService.getInstance().readText(Path.getAppInfoBackupMapPath())
                )
            }
            return appInfoBackupMap
        }

        /**
         * 构建应用恢复哈希表
         */
        suspend fun getAppInfoRestoreMap(onProgress: (Float) -> Unit = {}): AppInfoRestoreMap {
            var appInfoRestoreMap: AppInfoRestoreMap = hashMapOf()

            runOnIO {
                // 读取配置文件
                appInfoRestoreMap = GsonUtil.getInstance().fromAppInfoRestoreMapJson(
                    RootService.getInstance().readText(Path.getAppInfoRestoreMapPath())
                )

                // 根据备份目录实际文件调整列表
                execute("find \"${Path.getBackupDataSavePath()}\" -name \"*.tar*\" -type f").apply {
                    if (isSuccess) {
                        this.out.add("///") // 添加尾部元素, 保证原尾部元素参与

                        var appInfoRestore = AppInfoRestore()
                        var detailRestoreList = mutableListOf<AppInfoDetailRestore>()
                        var hasApp = false
                        var hasData = false
                        val outSize = this.out.size
                        for ((index, i) in this.out.withIndex()) {
                            try {
                                if (index < this.out.size - 1) {
                                    val info =
                                        i.replace(Path.getBackupDataSavePath(), "").split("/")
                                    val infoNext =
                                        this.out[index + 1].replace(
                                            Path.getBackupDataSavePath(),
                                            ""
                                        )
                                            .split("/")
                                    val packageName = info[1]
                                    val packageNameNext = infoNext[1]
                                    val date = info[2]
                                    val dateNext = infoNext[2]
                                    val fileName = info[3]

                                    if (info.size == 4) {
                                        if (fileName.contains("apk.tar"))
                                            hasApp = true
                                        else if (fileName.contains("data.tar"))
                                            hasData = true
                                        else if (fileName.contains("obb.tar"))
                                            hasData = true
                                        else if (fileName.contains("user.tar"))
                                            hasData = true
                                        else if (fileName.contains("user_de.tar"))
                                            hasData = true

                                        if (packageName != appInfoRestore.detailBase.packageName) {
                                            if (appInfoRestoreMap.containsKey(packageName).not()) {
                                                appInfoRestoreMap[packageName] =
                                                    AppInfoRestore().apply {
                                                        this.detailBase.appName =
                                                            GlobalString.appNameIsMissing
                                                    }
                                            }
                                            appInfoRestore = appInfoRestoreMap[packageName]!!
                                        }

                                        if (date != dateNext || packageName != packageNameNext) {
                                            // 与下一路径不同日期

                                            val detailListIndex =
                                                appInfoRestore.detailRestoreList.indexOfFirst { date == it.date }
                                            val detail =
                                                if (detailListIndex == -1) AppInfoDetailRestore().apply {
                                                    this.date = date
                                                } else appInfoRestore.detailRestoreList[detailListIndex]
                                            detail.apply {
                                                this.hasApp.value = hasApp
                                                this.hasData.value = hasData
                                                this.selectApp.value =
                                                    this.selectApp.value && hasApp
                                                this.selectData.value =
                                                    this.selectData.value && hasData
                                            }

                                            detailRestoreList.add(detail)
                                        }
                                        if (packageName != packageNameNext) {
                                            appInfoRestore.detailRestoreList = detailRestoreList
                                            appInfoRestore.detailBase.packageName = packageName
                                            detailRestoreList = mutableListOf()
                                            hasApp = false
                                            hasData = false
                                        }
                                    }
                                }
                            } catch (e: Exception) {
                                e.printStackTrace()
                            }
                            onProgress((index + 1).toFloat() / outSize)
                        }
                    }
                }

                // Empty restore list check
                val keys = mutableSetOf<String>()
                keys.addAll(appInfoRestoreMap.keys)
                for (i in keys) {
                    appInfoRestoreMap[i]?.apply {
                        if (this.detailRestoreList.isEmpty()) {
                            appInfoRestoreMap.remove(i)
                        } else {
                            this.isOnThisDevice.value = RootService.getInstance()
                                .queryInstalled(i, App.globalContext.readRestoreUser().toInt())
                        }
                    }
                }
            }
            appInfoRestoreMap.remove("")
            return appInfoRestoreMap
        }

        /**
         * 构建媒体备份哈希表
         */
        suspend fun getMediaInfoBackupMap(onProgress: (Float) -> Unit = {}): MediaInfoBackupMap {
            var mediaInfoBackupMap: MediaInfoBackupMap = hashMapOf()

            runOnIO {
                // 读取配置文件
                mediaInfoBackupMap = GsonUtil.getInstance().fromMediaInfoBackupMapJson(
                    RootService.getInstance().readText(Path.getMediaInfoBackupMapPath())
                )

                // 如果为空, 添加默认路径
                if (mediaInfoBackupMap.isEmpty()) {
                    val nameList = listOf("Pictures", "Download", "Music", "DCIM")
                    val pathList = listOf(
                        "/storage/emulated/0/Pictures",
                        "/storage/emulated/0/Download",
                        "/storage/emulated/0/Music",
                        "/storage/emulated/0/DCIM"
                    )
                    for ((index, _) in nameList.withIndex()) {
                        mediaInfoBackupMap[nameList[index]] = MediaInfoBackup().apply {
                            this.name = nameList[index]
                            this.path = pathList[index]
                            this.backupDetail.apply {
                                this.data.value = false
                                this.size = ""
                                this.date = ""
                            }
                        }
                    }
                }

                val mediaInfoBackupSize = mediaInfoBackupMap.values.size
                for ((index, i) in mediaInfoBackupMap.values.withIndex()) {
                    i.storageStats.dataBytes = RootService.getInstance().countSize(i.path)
                    onProgress((index + 1).toFloat() / mediaInfoBackupSize)
                }
            }
            return mediaInfoBackupMap
        }

        /**
         * 构建媒体恢复哈希表
         */
        suspend fun getMediaInfoRestoreMap(onProgress: (Float) -> Unit = {}): MediaInfoRestoreMap {
            var mediaInfoRestoreMap: MediaInfoRestoreMap = hashMapOf()

            runOnIO {
                // 读取配置文件
                mediaInfoRestoreMap = GsonUtil.getInstance().fromMediaInfoRestoreMapJson(
                    RootService.getInstance().readText(Path.getMediaInfoRestoreMapPath())
                )

                // 根据备份目录实际文件调整列表
                execute("find \"${Path.getBackupMediaSavePath()}\" -name \"*.tar*\" -type f").apply {
                    if (isSuccess) {
                        this.out.add("///") // 添加尾部元素, 保证原尾部元素参与

                        var mediaInfoRestore = MediaInfoRestore()
                        var detailRestoreList = mutableListOf<MediaInfoDetailBase>()
                        var hasData = false

                        val outSize = this.out.size
                        for ((index, i) in this.out.withIndex()) {
                            try {
                                if (index < this.out.size - 1) {
                                    val info =
                                        i.replace(Path.getBackupMediaSavePath(), "").split("/")
                                    val infoNext =
                                        this.out[index + 1].replace(
                                            Path.getBackupMediaSavePath(),
                                            ""
                                        )
                                            .split("/")
                                    val name = info[1]
                                    val nameNext = infoNext[1]
                                    val date = info[2]
                                    val dateNext = infoNext[2]
                                    val fileName = info[3]

                                    if (info.size == 4) {
                                        if (fileName.contains("${name}.tar"))
                                            hasData = true

                                        if (name != mediaInfoRestore.name) {
                                            if (mediaInfoRestoreMap.containsKey(name).not()) {
                                                mediaInfoRestoreMap[name] =
                                                    MediaInfoRestore().apply {
                                                        this.name = name
                                                    }
                                            }
                                            mediaInfoRestore = mediaInfoRestoreMap[name]!!
                                        }

                                        if (date != dateNext || name != nameNext) {
                                            // 与下一路径不同日期

                                            val detailListIndex =
                                                mediaInfoRestore.detailRestoreList.indexOfFirst { date == it.date }
                                            val detail =
                                                if (detailListIndex == -1) MediaInfoDetailBase().apply {
                                                    this.date = date
                                                } else mediaInfoRestore.detailRestoreList[detailListIndex]
                                            detail.apply {
                                                this.data.value = this.data.value && hasData
                                            }

                                            detailRestoreList.add(detail)
                                        }
                                        if (name != nameNext) {
                                            mediaInfoRestore.detailRestoreList = detailRestoreList
                                            mediaInfoRestore.name = name
                                            detailRestoreList = mutableListOf()
                                            hasData = false
                                        }
                                    }
                                }
                            } catch (e: Exception) {
                                e.printStackTrace()
                            }
                            onProgress((index + 1).toFloat() / outSize)
                        }
                    }
                }

                // Empty restore list check
                val keys = mutableSetOf<String>()
                keys.addAll(mediaInfoRestoreMap.keys)
                for (i in keys) {
                    mediaInfoRestoreMap[i]?.apply {
                        if (this.detailRestoreList.isEmpty()) {
                            mediaInfoRestoreMap.remove(i)
                        }
                    }
                }
            }
            mediaInfoRestoreMap.remove("")
            return mediaInfoRestoreMap
        }

        /**
         * 读取备份记录
         */
        suspend fun getBackupInfoList(): BackupInfoList {
            var backupInfoList: BackupInfoList = mutableListOf()

            runOnIO {
                // 读取配置文件
                backupInfoList = GsonUtil.getInstance().fromBackupInfoListJson(
                    RootService.getInstance().readText(Path.getBackupInfoListPath())
                )
            }
            return backupInfoList
        }

        /**
         * 读取黑名单
         */
        suspend fun readBlackListMap(path: String): BlackListMap {
            var blackListMap: BlackListMap = hashMapOf()

            runOnIO {
                // 读取配置文件
                blackListMap = GsonUtil.getInstance().fromBlackListMapJson(
                    RootService.getInstance().readText(path)
                )
            }
            return blackListMap
        }

        /**
         * 添加黑名单
         */
        suspend fun addBlackList(path: String, blackListItem: BlackListItem) {
            var blackListMap: BlackListMap

            runOnIO {
                // 读取配置文件
                blackListMap = GsonUtil.getInstance().fromBlackListMapJson(
                    RootService.getInstance().readText(path)
                )
                blackListMap[blackListItem.packageName] = blackListItem
                GsonUtil.getInstance().saveBlackListMapToFile(path, blackListMap)
            }
        }

        /**
         * 移除黑名单
         */
        suspend fun removeBlackList(path: String, packageName: String) {
            var blackListMap: BlackListMap

            runOnIO {
                // 读取配置文件
                blackListMap = GsonUtil.getInstance().fromBlackListMapJson(
                    RootService.getInstance().readText(path)
                )
                blackListMap.remove(packageName)
                GsonUtil.getInstance().saveBlackListMapToFile(path, blackListMap)
            }
        }

        /**
         * 释放资源文件
         */
        fun releaseAssets(mContext: Context, assetsPath: String, outName: String) {
            try {
                val assets = File(Path.getAppInternalFilesPath(), outName)
                if (!assets.exists()) {
                    val outStream = FileOutputStream(assets)
                    val inputStream = mContext.resources.assets.open(assetsPath)
                    inputStream.copyTo(outStream)
                    assets.setExecutable(true)
                    assets.setReadable(true)
                    assets.setWritable(true)
                    outStream.flush()
                    inputStream.close()
                    outStream.close()
                }
            } catch (e: IOException) {
                e.printStackTrace()
            }
        }

        /**
         * 获取ABI
         */
        fun getABI(): String {
            val mABIs = Build.SUPPORTED_ABIS
            if (mABIs.isNotEmpty()) {
                return mABIs[0]
            }
            return ""
        }

        /**
         * 压缩
         */
        suspend fun compress(
            compressionType: String,
            dataType: String,
            packageName: String,
            outPut: String,
            dataPath: String,
            dataSize: String? = null,
            compatibleMode: Boolean,
            updateState: (type: String, line: String?) -> Unit = { _, _ -> }
        ): Boolean {
            val tag = "compress"
            var needUpdate = true
            val filePath = if (dataType == "media") {
                "${outPut}/${packageName}.${getSuffixByCompressionType(compressionType)}"
            } else {
                "${outPut}/${dataType}.${getSuffixByCompressionType(compressionType)}"
            }

            if (App.globalContext.readBackupStrategy() == BackupStrategy.Cover) {
                // 当备份策略为覆盖时, 计算目录大小并判断是否更新
                if (dataType == "media") {
                    if (RootService.getInstance().countSize(dataPath).toString() == dataSize) {
                        needUpdate = false
                        Logcat.getInstance()
                            .actionLogAddLine(tag, "$dataPath may have no update.")
                    }
                } else {
                    if (RootService.getInstance().countSize("${dataPath}/${packageName}")
                            .toString() == dataSize
                    ) {
                        needUpdate = false
                        Logcat.getInstance()
                            .actionLogAddLine(tag, "${dataPath}/${packageName} may have no update.")
                    }
                }
                // 检测是否实际存在压缩包, 若不存在则仍然更新
                ls(filePath).apply {
                    if (!this) {
                        needUpdate = true
                        Logcat.getInstance()
                            .actionLogAddLine(tag, "$filePath is missing, needs update.")
                    }
                }
            }
            if (needUpdate) {
                updateState(ProcessCompressing, null)
                val (compressSuccess, out) = Bashrc.compress(
                    compressionType,
                    dataType,
                    packageName,
                    outPut,
                    dataPath,
                    compatibleMode
                )
                if (compressSuccess.not()) {
                    updateState(ProcessError, out)
                    Logcat.getInstance().actionLogAddLine(tag, out)
                    return false
                } else {
                    updateState(ProcessShowTotal, out)
                    Logcat.getInstance()
                        .actionLogAddLine(tag, "$dataType compressed.")
                }
            } else {
                updateState(ProcessSkip, null)
                Logcat.getInstance().actionLogAddLine(tag, "No update, skip.")
            }
            // 检测是否生成压缩包
            ls(filePath).apply {
                if (!this) {
                    "$filePath is missing, compressing may failed.".apply {
                        updateState(ProcessError, this)
                        Logcat.getInstance().actionLogAddLine(tag, this)
                    }
                    return false
                } else {
                    if (App.globalContext.readIsBackupTest()) {
                        // 校验
                        Logcat.getInstance().actionLogAddLine(tag, "Test ${filePath}.")
                        updateState(ProcessTesting, null)
                        val (testArchiveSuccess, _) = testArchive(compressionType, filePath)
                        if (testArchiveSuccess.not()) {
                            "Test failed. The broken file has been deleted.".apply {
                                RootService.getInstance().deleteRecursively(filePath)
                                updateState(ProcessError, this)
                                Logcat.getInstance().actionLogAddLine(tag, this)
                            }
                            return false
                        } else {
                            Logcat.getInstance().actionLogAddLine(tag, "Test passed.")
                        }
                    }
                }
            }
            updateState(ProcessFinished, null)
            return true
        }

        /**
         * 压缩APK
         */
        suspend fun compressAPK(
            compressionType: String,
            packageName: String,
            outPut: String,
            userId: String,
            apkSize: String? = null,
            compatibleMode: Boolean,
            updateState: (type: String, line: String?) -> Unit = { _, _ -> }
        ): Boolean {
            val tag = "compressAPK"
            var needUpdate = true
            val filePath = "${outPut}/apk.${getSuffixByCompressionType(compressionType)}"
            // 获取应用APK路径
            val (getAPKPathSuccess, apkPath) = Bashrc.getAPKPath(packageName, userId)
            if (getAPKPathSuccess.not()) {
                "Failed to get $packageName APK path.".apply {
                    updateState(ProcessError, this)
                    Logcat.getInstance().actionLogAddLine(tag, this)
                }
                return false
            } else {
                Logcat.getInstance().actionLogAddLine(tag, "$packageName APK path: ${apkPath}.")
            }
            if (App.globalContext.readBackupStrategy() == BackupStrategy.Cover) {
                // 当备份策略为覆盖时, 计算目录大小并判断是否更新
                if (RootService.getInstance().countSize(apkPath).toString() == apkSize) {
                    needUpdate = false
                    Logcat.getInstance()
                        .actionLogAddLine(tag, "$apkPath may have no update.")
                }
                // 检测是否实际存在压缩包, 若不存在则仍然更新
                ls(filePath).apply {
                    // 后续若直接令state = this会导致state非正常更新
                    if (!this) {
                        needUpdate = true
                        Logcat.getInstance()
                            .actionLogAddLine(tag, "$filePath is missing, needs update.")
                    }
                }
            }
            if (needUpdate) {
                updateState(ProcessCompressing, null)
                val (compressAPKSuccess, out) = Bashrc.compressAPK(
                    compressionType,
                    apkPath,
                    outPut,
                    compatibleMode
                )
                if (compressAPKSuccess.not()) {
                    updateState(ProcessError, out)
                    Logcat.getInstance().actionLogAddLine(tag, out)
                    return false
                } else {
                    updateState(ProcessShowTotal, out)
                    Logcat.getInstance()
                        .actionLogAddLine(tag, "Apk compressed.")
                }
            } else {
                updateState(ProcessSkip, null)
                Logcat.getInstance().actionLogAddLine(tag, "No update, skip.")
            }
            // 检测是否生成压缩包
            ls(filePath).apply {
                // 后续若直接令state = this会导致state非正常更新
                if (!this) {
                    "$filePath is missing, compressing may failed.".apply {
                        updateState(ProcessError, this)
                        Logcat.getInstance().actionLogAddLine(tag, this)
                    }
                    return false
                } else {
                    if (App.globalContext.readIsBackupTest()) {
                        // 校验
                        Logcat.getInstance().actionLogAddLine(tag, "Test ${filePath}.")
                        updateState(ProcessTesting, null)
                        val (testArchiveSuccess, _) = testArchive(compressionType, filePath)
                        if (testArchiveSuccess.not()) {
                            "Test failed. The broken file has been deleted.".apply {
                                RootService.getInstance().deleteRecursively(filePath)
                                updateState(ProcessError, this)
                                Logcat.getInstance().actionLogAddLine(tag, this)
                            }
                            return false
                        } else {
                            Logcat.getInstance().actionLogAddLine(tag, "Test passed.")
                        }
                    }
                }
            }
            updateState(ProcessFinished, null)
            return true
        }

        /**
         * 解压
         */
        suspend fun decompress(
            compressionType: String,
            dataType: String,
            inputPath: String,
            packageName: String,
            dataPath: String,
            updateState: (type: String, line: String?) -> Unit = { _, _ -> }
        ): Boolean {
            val tag = "decompress"
            updateState(ProcessDecompressing, null)
            val (decompressSuccess, out) = Bashrc.decompress(
                compressionType,
                dataType,
                inputPath,
                packageName,
                dataPath
            )
            if (decompressSuccess.not()) {
                updateState(ProcessError, out)
                Logcat.getInstance().actionLogAddLine(tag, out)
                return false
            } else {
                updateState(ProcessShowTotal, out)
                Logcat.getInstance()
                    .actionLogAddLine(tag, "$dataType decompressed.")
            }
            updateState(ProcessFinished, null)
            return true
        }

        /**
         * 安装APK
         */
        suspend fun installAPK(
            inPath: String,
            packageName: String,
            userId: String,
            versionCode: String,
            updateState: (type: String, line: String?) -> Unit = { _, _ -> }
        ): Boolean {
            val tag = "installAPK"
            val (getAppVersionCodeSuccess, appVersionCode) = getAppVersionCode(userId, packageName)
            if (getAppVersionCodeSuccess.not()) {
                Logcat.getInstance()
                    .actionLogAddLine(tag, "Failed to get $packageName version code.")
            } else {
                Logcat.getInstance()
                    .actionLogAddLine(tag, "$packageName version code: ${appVersionCode}.")
            }
            Logcat.getInstance().actionLogAddLine(
                tag,
                "versionCode: ${versionCode}, actual appVersionCode: ${appVersionCode}."
            )
            // 禁止APK验证
            val (setInstallEnvSuccess, out) = Bashrc.setInstallEnv()
            if (setInstallEnvSuccess.not()) {
                "Failed to set install env.".apply {
                    Logcat.getInstance().actionLogAddLine(tag, this)
                    Logcat.getInstance().actionLogAddLine(tag, out)
                }
            }

            // 安装APK
            updateState(ProcessInstallingApk, null)
            val (installAPKSuccess, installAPKOut) = Bashrc.installAPK(
                inPath,
                packageName,
                userId
            )
            if (installAPKSuccess.not()) {
                updateState(ProcessError, installAPKOut)
                Logcat.getInstance().actionLogAddLine(tag, installAPKOut)
                return false
            } else {
                updateState(ProcessShowTotal, installAPKOut)
                Logcat.getInstance()
                    .actionLogAddLine(tag, "Apk installed.")
            }

            Bashrc.findPackage(userId, packageName).apply {
                if (this.first.not()) {
                    "Package: $packageName not found.".apply {
                        updateState(ProcessError, this)
                        Logcat.getInstance().actionLogAddLine(tag, this)
                    }
                    return false
                }
            }
            updateState(ProcessFinished, null)
            return true
        }

        /**
         * 配置Owner以及SELinux相关
         */
        suspend fun setOwnerAndSELinux(
            dataType: String,
            packageName: String,
            path: String,
            userId: String,
            context: String,
            updateState: (type: String, line: String?) -> Unit = { _, _ -> }
        ): Boolean {
            val tag = "setOwnerAndSELinux"
            updateState(ProcessSettingSELinux, null)
            val (setOwnerAndSELinuxSuccess, out) = Bashrc.setOwnerAndSELinux(
                dataType,
                packageName,
                path,
                userId,
                App.globalContext.readAutoFixMultiUserContext(),
                context
            )
            if (setOwnerAndSELinuxSuccess.not()) {
                updateState(ProcessError, out)
                Logcat.getInstance().actionLogAddLine(tag, out)
                return false
            } else {
                Logcat.getInstance()
                    .actionLogAddLine(tag, "$dataType setOwnerAndSELinux finished.")
            }
            updateState(ProcessFinished, null)
            return true
        }

        /**
         * 获取应用版本代码
         */
        private suspend fun getAppVersionCode(
            userId: String,
            packageName: String
        ): Pair<Boolean, String> {
            return Bashrc.getAppVersionCode(userId, packageName)
        }

        /**
         * 测试压缩包
         */
        private suspend fun testArchive(
            compressionType: String,
            inputPath: String,
        ): Pair<Boolean, String> {
            return Bashrc.testArchive(compressionType, inputPath)
        }

        /**
         * 备份自身
         */
        suspend fun backupItself(
            packageName: String, outPut: String, userId: String
        ): Boolean {
            mkdir(outPut)
            val apkPath = Bashrc.getAPKPath(packageName, userId)
            val apkPathPair = apkPath.apply {
                if (!this.first) {
                    return false
                }
            }

            val apkSize = RootService.getInstance().countSize("${outPut}/DataBackup.apk").toString()
            if (RootService.getInstance().countSize(apkPathPair.second)
                    .toString() == apkSize
            ) return true
            cp("${apkPathPair.second}/base.apk", "${outPut}/DataBackup.apk").apply {
                if (!this) {
                    return false
                }
            }
            return true
        }

        /**
         * 检查ROOT
         */
        suspend fun checkRoot(): Boolean {
            return withContext(Dispatchers.IO) {
                execute(
                    "ls /",
                    false
                ).isSuccess && Shell.getCachedShell()?.isRoot == true
            }
        }

        /**
         * 检查二进制文件
         */
        suspend fun checkBin(): Boolean {
            val binList = listOf("df", "chmod", "tar", "zstd")
            execute("ls -l \"${Path.getAppInternalFilesPath()}/bin\" | awk '{print \$1, \$8}'").out.apply {
                val fileList = this.subList(1, this.size)
                for (i in binList) {
                    var granted = false
                    for (j in fileList) {
                        try {
                            val (permission, name) = j.split(" ")
                            if (name == i && permission == "-rwxrwxrwx") {
                                granted = true
                                break
                            }
                        } catch (e: Exception) {
                            continue
                        }
                    }
                    if (granted.not()) return false
                }
                return true
            }
        }

        /**
         * 检查Bash环境
         */
        suspend fun checkBashrc(): Boolean {
            return execute("check_bashrc").isSuccess
        }

        /**
         * 获取本应用版本名称
         */
        fun getVersion(): String {
            return App.globalContext.packageManager.getPackageInfo(
                App.globalContext.packageName, 0
            ).versionName
        }

        /**
         * 获取日期, `timeStamp`为空时获取当前日期, 否则为时间戳转日期
         */
        fun getDate(timeStamp: String = ""): String {
            var date: String
            try {
                SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA).apply {
                    date = if (timeStamp == "") {
                        format(Date())
                    } else {
                        format(Date(timeStamp.toLong()))
                    }
                }
            } catch (e: Exception) {
                date = timeStamp
                e.printStackTrace()
            }
            return date
        }

        /**
         * 通过路径解析压缩方式
         */
        suspend fun getCompressionTypeByPath(path: String): String {
            execute("ls \"$path\"").out.joinToLineString.apply {
                return try {
                    when (this.split("/").last().split(".").last()) {
                        "tar" -> "tar"
                        "lz4" -> "lz4"
                        "zst" -> "zstd"
                        else -> ""
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                    ""
                }
            }
        }

        /**
         * 通过压缩方式得到后缀
         */
        fun getSuffixByCompressionType(type: String): String {
            return when (type) {
                "tar" -> "tar"
                "lz4" -> "tar.lz4"
                "zstd" -> "tar.zst"
                else -> ""
            }
        }

        /**
         * 经由Log封装的执行函数
         */
        suspend fun execute(cmd: String, isAddToLog: Boolean = true): Shell.Result {
            val result = runOnIO {
                if (isAddToLog)
                    Logcat.getInstance().shellLogAddLine("SHELL_IN: $cmd")
                Shell.cmd(cmd).exec().apply {
                    if (isAddToLog)
                        for (i in this.out)
                            Logcat.getInstance().shellLogAddLine("SHELL_OUT: $i")
                }
            }

            if (result.code == 127) {
                // 当exit code为127时, 环境可能丢失
                App.initShell(App.globalContext, Shell.getShell())
            }

            return result
        }

        /**
         * 检查`ls -Zd`命令是否可用
         */
        suspend fun checkLsZd(): Boolean {
            return execute("ls -Zd").isSuccess
        }

        /**
         * 列出备份用户
         */
        suspend fun listBackupUsers(): MutableList<String> {
            val exec = execute("ls \"${Path.getBackupUserPath()}\"")
            val users = mutableListOf<String>()
            for (i in exec.out) {
                try {
                    i.toInt()
                    users.add(i)
                } catch (e: NumberFormatException) {
                    e.printStackTrace()
                }
            }
            return users
        }

        suspend fun getSmsList(context: Context, readOnly: Boolean): SmsList {
            var smsList: SmsList = mutableListOf()

            runOnIO {
                // Read from storage
                smsList = GsonUtil.getInstance().fromSmsListJson(
                    RootService.getInstance().readText(Path.getSmsListPath())
                )
                smsList.forEach {
                    it.isSelected = mutableStateOf(readOnly)
                    it.isInLocal = mutableStateOf(true)
                    it.isOnThisDevice = mutableStateOf(false)
                }

                context.contentResolver.query(
                    Telephony.Sms.CONTENT_URI,
                    null,
                    null,
                    null,
                    Telephony.Sms.DEFAULT_SORT_ORDER
                )?.apply {
                    val tmpList: SmsList = mutableListOf()
                    while (moveToNext()) {
                        try {
                            val address =
                                getString(getColumnIndexOrThrow(Telephony.Sms.ADDRESS)) ?: ""
                            val body = getString(getColumnIndexOrThrow(Telephony.Sms.BODY)) ?: ""
                            val creator =
                                getString(getColumnIndexOrThrow(Telephony.Sms.CREATOR)) ?: ""
                            val date = getLong(getColumnIndexOrThrow(Telephony.Sms.DATE))
                            val dateSent = getLong(getColumnIndexOrThrow(Telephony.Sms.DATE_SENT))
                            val errorCode = getLong(getColumnIndexOrThrow(Telephony.Sms.ERROR_CODE))
                            val locked = getLong(getColumnIndexOrThrow(Telephony.Sms.LOCKED))
                            val person = getLong(getColumnIndexOrThrow(Telephony.Sms.PERSON))
                            val protocol = getLong(getColumnIndexOrThrow(Telephony.Sms.PROTOCOL))
                            val read = getLong(getColumnIndexOrThrow(Telephony.Sms.READ))
                            val replyPathPresent =
                                getLong(getColumnIndexOrThrow(Telephony.Sms.REPLY_PATH_PRESENT))
                            val seen = getLong(getColumnIndexOrThrow(Telephony.Sms.SEEN))
                            val serviceCenter =
                                getString(getColumnIndexOrThrow(Telephony.Sms.SERVICE_CENTER)) ?: ""
                            val status = getLong(getColumnIndexOrThrow(Telephony.Sms.STATUS))
                            val subject =
                                getString(getColumnIndexOrThrow(Telephony.Sms.SUBJECT)) ?: ""
                            val subscriptionId =
                                getLong(getColumnIndexOrThrow(Telephony.Sms.SUBSCRIPTION_ID))
                            val type = getLong(getColumnIndexOrThrow(Telephony.Sms.TYPE))

                            var exist = false
                            for (i in smsList) {
                                // Check if it already exists
                                if (i.address == address && i.body == body && i.date == date && i.dateSent == dateSent && i.type == type) {
                                    i.isSelected.value = false
                                    i.isOnThisDevice.value = true
                                    exist = true
                                    break
                                }
                            }
                            if (exist) continue

                            if (readOnly.not())
                                tmpList.add(
                                    SmsItem(
                                        address = address,
                                        body = body,
                                        creator = creator,
                                        date = date,
                                        dateSent = dateSent,
                                        errorCode = errorCode,
                                        locked = locked,
                                        person = person,
                                        protocol = protocol,
                                        read = read,
                                        replyPathPresent = replyPathPresent,
                                        seen = seen,
                                        serviceCenter = serviceCenter,
                                        status = status,
                                        subject = subject,
                                        subscriptionId = subscriptionId,
                                        type = type,
                                        isSelected = mutableStateOf(true),
                                        isInLocal = mutableStateOf(false),
                                        isOnThisDevice = mutableStateOf(true),
                                    )
                                )
                        } catch (_: Exception) {
                        }
                    }
                    close()
                    smsList.addAll(tmpList)
                    smsList.sortByDescending { it.date }
                }
            }
            return smsList
        }

        suspend fun getMmsList(context: Context, readOnly: Boolean): MmsList {
            var mmsList: MmsList = mutableListOf()

            runOnIO {
                // Read from storage
                mmsList = GsonUtil.getInstance().fromMmsListJson(
                    RootService.getInstance().readText(Path.getMmsListPath())
                )
                mmsList.forEach {
                    it.isSelected = mutableStateOf(readOnly)
                    it.isInLocal = mutableStateOf(true)
                    it.isOnThisDevice = mutableStateOf(false)
                }

                context.contentResolver.query(
                    Telephony.Mms.CONTENT_URI,
                    null,
                    null,
                    null,
                    Telephony.Mms.DEFAULT_SORT_ORDER
                )?.apply {
                    val tmpList: MmsList = mutableListOf()
                    while (moveToNext()) {
                        try {
                            /**
                             * (pdu)_id -> (addr)msg_id
                             * (pdu)_id -> (part)mid
                             */

                            /**
                             * Get data from pdu table
                             */
                            val id = getLong(getColumnIndexOrThrow(Telephony.Mms._ID))
                            val contentClass =
                                getLong(getColumnIndexOrThrow(Telephony.Mms.CONTENT_CLASS))
                            val contentLocation =
                                getString(getColumnIndexOrThrow(Telephony.Mms.CONTENT_LOCATION))
                                    ?: ""
                            val contentType =
                                getString(getColumnIndexOrThrow(Telephony.Mms.CONTENT_TYPE)) ?: ""
                            val date = getLong(getColumnIndexOrThrow(Telephony.Mms.DATE))
                            val dateSent = getLong(getColumnIndexOrThrow(Telephony.Mms.DATE_SENT))
                            val deliveryReport =
                                getLong(getColumnIndexOrThrow(Telephony.Mms.DELIVERY_REPORT))
                            val deliveryTime =
                                getLong(getColumnIndexOrThrow(Telephony.Mms.DELIVERY_TIME))
                            val expiry = getLong(getColumnIndexOrThrow(Telephony.Mms.EXPIRY))
                            val locked = getLong(getColumnIndexOrThrow(Telephony.Mms.LOCKED))
                            val messageBox =
                                getLong(getColumnIndexOrThrow(Telephony.Mms.MESSAGE_BOX))
                            val messageClass =
                                getString(getColumnIndexOrThrow(Telephony.Mms.MESSAGE_CLASS)) ?: ""
                            val messageId =
                                getString(getColumnIndexOrThrow(Telephony.Mms.MESSAGE_ID)) ?: ""
                            val messageSize =
                                getLong(getColumnIndexOrThrow(Telephony.Mms.MESSAGE_SIZE))
                            val messageType =
                                getLong(getColumnIndexOrThrow(Telephony.Mms.MESSAGE_TYPE))
                            val mmsVersion =
                                getLong(getColumnIndexOrThrow(Telephony.Mms.MMS_VERSION))
                            val priority = getLong(getColumnIndexOrThrow(Telephony.Mms.PRIORITY))
                            val read = getLong(getColumnIndexOrThrow(Telephony.Mms.READ))
                            val readReport =
                                getLong(getColumnIndexOrThrow(Telephony.Mms.READ_REPORT))
                            val readStatus =
                                getLong(getColumnIndexOrThrow(Telephony.Mms.READ_STATUS))
                            val reportAllowed =
                                getLong(getColumnIndexOrThrow(Telephony.Mms.REPORT_ALLOWED))
                            val responseStatus =
                                getLong(getColumnIndexOrThrow(Telephony.Mms.RESPONSE_STATUS))
                            val responseText =
                                getString(getColumnIndexOrThrow(Telephony.Mms.RESPONSE_TEXT)) ?: ""
                            val retrieveStatus =
                                getLong(getColumnIndexOrThrow(Telephony.Mms.RETRIEVE_STATUS))
                            val retrieveText =
                                getString(getColumnIndexOrThrow(Telephony.Mms.RETRIEVE_TEXT)) ?: ""
                            val retrieveTextCharset =
                                getLong(getColumnIndexOrThrow(Telephony.Mms.RETRIEVE_TEXT_CHARSET))
                            val seen = getLong(getColumnIndexOrThrow(Telephony.Mms.SEEN))
                            val status = getLong(getColumnIndexOrThrow(Telephony.Mms.STATUS))
                            val subject =
                                getString(getColumnIndexOrThrow(Telephony.Mms.SUBJECT)) ?: ""
                            val subjectCharset =
                                getLong(getColumnIndexOrThrow(Telephony.Mms.SUBJECT_CHARSET))
                            val subscriptionId =
                                getLong(getColumnIndexOrThrow(Telephony.Mms.SUBSCRIPTION_ID))
                            val textOnly = getLong(getColumnIndexOrThrow(Telephony.Mms.TEXT_ONLY))
                            val transactionId =
                                getString(getColumnIndexOrThrow(Telephony.Mms.TRANSACTION_ID)) ?: ""
                            val pdu = MmsPduItem(
                                contentClass = contentClass,
                                contentLocation = contentLocation,
                                contentType = contentType,
                                date = date,
                                dateSent = dateSent,
                                deliveryReport = deliveryReport,
                                deliveryTime = deliveryTime,
                                expiry = expiry,
                                locked = locked,
                                messageBox = messageBox,
                                messageClass = messageClass,
                                messageId = messageId,
                                messageSize = messageSize,
                                messageType = messageType,
                                mmsVersion = mmsVersion,
                                priority = priority,
                                read = read,
                                readReport = readReport,
                                readStatus = readStatus,
                                reportAllowed = reportAllowed,
                                responseStatus = responseStatus,
                                responseText = responseText,
                                retrieveStatus = retrieveStatus,
                                retrieveText = retrieveText,
                                retrieveTextCharset = retrieveTextCharset,
                                seen = seen,
                                status = status,
                                subject = subject,
                                subjectCharset = subjectCharset,
                                subscriptionId = subscriptionId,
                                textOnly = textOnly,
                                transactionId = transactionId,
                            )

                            /**
                             * Get data from addr table
                             */
                            val addr = mutableListOf<MmsAddrItem>()
                            context.contentResolver.query(
                                Uri.parse("content://mms/$id/addr"),
                                null,
                                null,
                                null,
                                null
                            )?.apply {
                                while (moveToNext()) {
                                    try {
                                        val address =
                                            getString(getColumnIndexOrThrow(Telephony.Mms.Addr.ADDRESS))
                                                ?: ""
                                        val charset =
                                            getLong(getColumnIndexOrThrow(Telephony.Mms.Addr.CHARSET))
                                        val contactId =
                                            getLong(getColumnIndexOrThrow(Telephony.Mms.Addr.CONTACT_ID))
                                        val type =
                                            getLong(getColumnIndexOrThrow(Telephony.Mms.Addr.TYPE))
                                        addr.add(
                                            MmsAddrItem(
                                                address = address,
                                                charset = charset,
                                                contactId = contactId,
                                                type = type
                                            )
                                        )
                                    } catch (_: Exception) {
                                    }
                                }
                                close()
                            }

                            /**
                             * Get data from part table
                             */
                            val part = mutableListOf<MmsPartItem>()
                            context.contentResolver.query(
                                Uri.parse("content://mms/$id/part"),
                                null,
                                null,
                                null,
                                null
                            )?.apply {
                                while (moveToNext()) {
                                    try {
                                        val charset =
                                            getString(getColumnIndexOrThrow(Telephony.Mms.Part.CHARSET))
                                                ?: ""
                                        val contentDisposition =
                                            getString(getColumnIndexOrThrow(Telephony.Mms.Part.CONTENT_DISPOSITION))
                                                ?: ""
                                        val contentId =
                                            getString(getColumnIndexOrThrow(Telephony.Mms.Part.CONTENT_ID))
                                                ?: ""
                                        val partContentLocation =
                                            getString(getColumnIndexOrThrow(Telephony.Mms.Part.CONTENT_LOCATION))
                                                ?: ""
                                        val partContentType =
                                            getString(getColumnIndexOrThrow(Telephony.Mms.Part.CONTENT_TYPE))
                                                ?: ""
                                        val ctStart =
                                            getLong(getColumnIndexOrThrow(Telephony.Mms.Part.CT_START))
                                        val ctType =
                                            getString(getColumnIndexOrThrow(Telephony.Mms.Part.CT_TYPE))
                                                ?: ""
                                        val filename =
                                            getString(getColumnIndexOrThrow(Telephony.Mms.Part.FILENAME))
                                                ?: ""
                                        val name =
                                            getString(getColumnIndexOrThrow(Telephony.Mms.Part.NAME))
                                                ?: ""
                                        val seq =
                                            getLong(getColumnIndexOrThrow(Telephony.Mms.Part.SEQ))
                                        val text =
                                            getString(getColumnIndexOrThrow(Telephony.Mms.Part.TEXT))
                                                ?: ""
                                        val _data =
                                            getString(getColumnIndexOrThrow(Telephony.Mms.Part._DATA))
                                                ?: ""
                                        part.add(
                                            MmsPartItem(
                                                charset,
                                                contentDisposition,
                                                contentId,
                                                partContentLocation,
                                                partContentType,
                                                ctStart,
                                                ctType,
                                                filename,
                                                name,
                                                seq,
                                                text,
                                                _data,
                                            )
                                        )
                                    } catch (e: Exception) {
                                        e.printStackTrace()
                                    }
                                }
                                close()
                            }

                            val mmsItem = MmsItem(
                                pdu = pdu,
                                addr = addr,
                                part = part,
                                isSelected = mutableStateOf(true),
                                isInLocal = mutableStateOf(false),
                                isOnThisDevice = mutableStateOf(true),
                            )

                            var exist = false
                            for (i in mmsList) {
                                // Check if it already exists
                                if (i.address == mmsItem.address && i.smilText == mmsItem.smilText && i.plainText == mmsItem.plainText && i.pdu.date == mmsItem.pdu.date && i.pdu.dateSent == mmsItem.pdu.dateSent && i.pdu.messageType == mmsItem.pdu.messageType && i.pdu.messageSize == mmsItem.pdu.messageSize) {
                                    i.isSelected.value = false
                                    i.isOnThisDevice.value = true
                                    exist = true
                                    break
                                }
                            }
                            if (exist) continue

                            if (readOnly.not())
                                tmpList.add(mmsItem)
                        } catch (_: Exception) {
                        }
                    }
                    close()
                    mmsList.addAll(tmpList)
                    mmsList.sortByDescending { it.pdu.date }
                }
            }
            return mmsList
        }
    }
}
