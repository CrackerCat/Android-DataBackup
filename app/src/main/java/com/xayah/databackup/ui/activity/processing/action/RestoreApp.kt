package com.xayah.databackup.ui.activity.processing.action

import android.content.Context
import android.graphics.BitmapFactory
import androidx.appcompat.content.res.AppCompatResources
import androidx.compose.runtime.mutableStateOf
import androidx.core.graphics.drawable.toDrawable
import com.xayah.databackup.App
import com.xayah.databackup.R
import com.xayah.databackup.data.LoadingState
import com.xayah.databackup.data.ProcessError
import com.xayah.databackup.data.ProcessingObjectType
import com.xayah.databackup.data.TaskState
import com.xayah.databackup.ui.activity.processing.ProcessingViewModel
import com.xayah.databackup.ui.activity.processing.components.ProcessObjectItem
import com.xayah.databackup.ui.activity.processing.components.ProcessingTask
import com.xayah.databackup.ui.activity.processing.components.parseObjectItemBySrc
import com.xayah.databackup.util.*
import com.xayah.librootservice.RootService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

fun onRestoreAppProcessing(
    viewModel: ProcessingViewModel,
    context: Context,
    globalObject: GlobalObject,
    retry: Boolean = false,
) {
    if (viewModel.isFirst.value) {
        viewModel.isFirst.value = false
        CoroutineScope(Dispatchers.IO).launch {
            val tag = "RestoreApp"
            Logcat.getInstance().actionLogAddLine(tag, "===========${tag}===========")

            val loadingState = viewModel.loadingState
            val progress = viewModel.progress
            val topBarTitle = viewModel.topBarTitle
            val taskList = viewModel.taskList.value
            val objectList = viewModel.objectList.value.apply {
                clear()
                val typeList = listOf(
                    ProcessingObjectType.APP,
                    ProcessingObjectType.USER,
                    ProcessingObjectType.USER_DE,
                    ProcessingObjectType.DATA,
                    ProcessingObjectType.OBB,
                )
                for (i in typeList) {
                    add(ProcessObjectItem(type = i))
                }
            }
            val allDone = viewModel.allDone

            // 检查列表
            if (globalObject.appInfoRestoreMap.value.isEmpty()) {
                globalObject.appInfoRestoreMap.emit(Command.getAppInfoRestoreMap())
            }
            Logcat.getInstance().actionLogAddLine(tag, "Global map check finished.")

            if (retry.not()) {
                // 备份信息列表
                taskList.addAll(
                    globalObject.appInfoRestoreMap.value.values.toList()
                        .filter { if (it.detailRestoreList.isNotEmpty()) it.selectApp.value || it.selectData.value else false }
                        .map {
                            ProcessingTask(
                                appName = it.detailBase.appName,
                                packageName = it.detailBase.packageName,
                                appIcon = AppCompatResources.getDrawable(
                                    context,
                                    R.drawable.ic_round_android
                                ),
                                selectApp = it.selectApp.value,
                                selectData = it.selectData.value,
                                objectList = listOf()
                            ).apply {
                                if (App.globalContext.readIsReadIcon()) {
                                    try {
                                        val task = this
                                        val bytes = RootService.getInstance()
                                            .readBytes("${Path.getBackupDataSavePath()}/${it.detailBase.packageName}/icon.png")
                                        task.appIcon =
                                            (BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                                                .toDrawable(context.resources))
                                    } catch (e: Exception) {
                                        e.printStackTrace()
                                    }
                                }
                            }
                        })
            } else {
                Logcat.getInstance().actionLogAddLine(tag, "Retrying.")
            }

            Logcat.getInstance().actionLogAddLine(tag, "Task added, size: ${taskList.size}.")

            val userId = App.globalContext.readRestoreUser()
            val compressionType = App.globalContext.readCompressionType()

            Logcat.getInstance().actionLogAddLine(tag, "userId: ${userId}.")
            Logcat.getInstance().actionLogAddLine(tag, "CompressionType: ${compressionType}.")

            // 前期准备完成
            loadingState.value = LoadingState.Success
            topBarTitle.value =
                "${context.getString(R.string.restoring)}(${progress.value}/${taskList.size})"
            for ((index, i) in taskList.withIndex()) {
                // Skip while retrying not-failed task
                if (retry && i.taskState.value != TaskState.Failed) continue

                // 重置恢复目标
                for (j in objectList) {
                    j.apply {
                        state.value = TaskState.Waiting
                        title.value = GlobalString.ready
                        visible.value = false
                        subtitle.value = GlobalString.pleaseWait
                    }
                }

                // 进入Processing状态
                i.taskState.value = TaskState.Processing
                val appInfoRestore =
                    globalObject.appInfoRestoreMap.value[i.packageName]!!

                // 滑动至目标应用
                if (viewModel.listStateIsInitialized) {
                    if (viewModel.scopeIsInitialized) {
                        viewModel.scope.launch {
                            viewModel.listState.animateScrollToItem(index)
                        }
                    }
                }

                var isSuccess = true
                val packageName = i.packageName
                val date = appInfoRestore.detailRestoreList[appInfoRestore.restoreIndex].date
                val inPath = "${Path.getBackupDataSavePath()}/${packageName}/${date}"
                val suffix = Command.getSuffixByCompressionType(compressionType)
                val userPath = "${inPath}/user.$suffix"
                val userDePath = "${inPath}/user_de.$suffix"
                val dataPath = "${inPath}/data.$suffix"
                val obbPath = "${inPath}/obb.$suffix"

                Logcat.getInstance().actionLogAddLine(tag, "AppName: ${i.appName}.")
                Logcat.getInstance().actionLogAddLine(tag, "PackageName: ${i.packageName}.")

                if (i.selectApp) {
                    // 检查是否备份APK
                    objectList[0].visible.value = true
                }
                if (i.selectData) {
                    // 检查是否备份数据
                    // USER为必备份项
                    objectList[1].visible.value = true
                    // 检测是否存在USER_DE
                    Command.ls(userDePath).apply {
                        if (this) {
                            objectList[2].visible.value = true
                        }
                    }
                    // 检测是否存在DATA
                    Command.ls(dataPath).apply {
                        if (this) {
                            objectList[3].visible.value = true
                        }
                    }
                    // 检测是否存在OBB
                    Command.ls(obbPath).apply {
                        if (this) {
                            objectList[4].visible.value = true
                        }
                    }
                }
                for ((jIndex, j) in objectList.withIndex()) {
                    if (viewModel.isCancel.value) break
                    if (j.visible.value) {
                        j.state.value = TaskState.Processing
                        when (j.type) {
                            ProcessingObjectType.APP -> {
                                isSuccess = Command.installAPK(
                                    inPath,
                                    packageName,
                                    userId,
                                    appInfoRestore.detailRestoreList[appInfoRestore.restoreIndex].versionCode.toString()
                                ) { type, line ->
                                    parseObjectItemBySrc(type, line ?: "", j)
                                }

                                // 如果未安装该应用, 则无法完成后续恢复
                                if (!isSuccess) {
                                    for (k in jIndex + 1 until objectList.size) {
                                        parseObjectItemBySrc(
                                            ProcessError,
                                            "Apk not installed.",
                                            objectList[k]
                                        )
                                    }
                                    break
                                }
                            }
                            ProcessingObjectType.USER -> {
                                // 读取原有SELinux context
                                val contextSELinux =
                                    Bashrc.getSELinuxContext("${Path.getUserPath(userId)}/${packageName}")
                                // 恢复User
                                Command.decompress(
                                    Command.getCompressionTypeByPath(userPath),
                                    "user",
                                    userPath,
                                    packageName,
                                    Path.getUserPath(userId)
                                ) { type, line ->
                                    parseObjectItemBySrc(type, line ?: "", j)
                                }.apply {
                                    if (!this) isSuccess = false
                                }
                                Command.setOwnerAndSELinux(
                                    "user",
                                    packageName,
                                    "${Path.getUserPath(userId)}/${packageName}",
                                    userId,
                                    contextSELinux
                                ) { type, line ->
                                    parseObjectItemBySrc(type, line ?: "", j)
                                }.apply {
                                    if (!this) isSuccess = false
                                }
                            }
                            ProcessingObjectType.USER_DE -> {
                                // 读取原有SELinux context
                                val contextSELinux =
                                    Bashrc.getSELinuxContext("${Path.getUserDePath(userId)}/${packageName}")
                                // 恢复User_de
                                Command.decompress(
                                    Command.getCompressionTypeByPath(userDePath),
                                    "user_de",
                                    userDePath,
                                    packageName,
                                    Path.getUserDePath(userId)
                                ) { type, line ->
                                    parseObjectItemBySrc(type, line ?: "", j)
                                }.apply {
                                    if (!this) isSuccess = false
                                }
                                Command.setOwnerAndSELinux(
                                    "user_de",
                                    packageName,
                                    "${Path.getUserDePath(userId)}/${packageName}",
                                    userId,
                                    contextSELinux
                                ) { type, line ->
                                    parseObjectItemBySrc(type, line ?: "", j)
                                }.apply {
                                    if (!this) isSuccess = false
                                }
                            }
                            ProcessingObjectType.DATA -> {
                                // 读取原有SELinux context
                                val contextSELinux =
                                    Bashrc.getSELinuxContext("${Path.getDataPath(userId)}/${packageName}")
                                // 恢复Data
                                Command.decompress(
                                    Command.getCompressionTypeByPath(dataPath),
                                    "data",
                                    dataPath,
                                    packageName,
                                    Path.getDataPath(userId)
                                ) { type, line ->
                                    parseObjectItemBySrc(type, line ?: "", j)
                                }.apply {
                                    if (!this) isSuccess = false
                                }
                                Command.setOwnerAndSELinux(
                                    "data",
                                    packageName,
                                    "${Path.getDataPath(userId)}/${packageName}",
                                    userId,
                                    contextSELinux
                                ) { type, line ->
                                    parseObjectItemBySrc(type, line ?: "", j)
                                }.apply {
                                    if (!this) isSuccess = false
                                }
                            }
                            ProcessingObjectType.OBB -> {
                                // 读取原有SELinux context
                                val contextSELinux =
                                    Bashrc.getSELinuxContext("${Path.getObbPath(userId)}/${packageName}")
                                // 恢复Obb
                                Command.decompress(
                                    Command.getCompressionTypeByPath(obbPath),
                                    "obb",
                                    obbPath,
                                    packageName,
                                    Path.getObbPath(userId)
                                ) { type, line ->
                                    parseObjectItemBySrc(type, line ?: "", j)
                                }.apply {
                                    if (!this) isSuccess = false
                                }
                                Command.setOwnerAndSELinux(
                                    "obb",
                                    packageName,
                                    "${Path.getObbPath(userId)}/${packageName}",
                                    userId,
                                    contextSELinux
                                ) { type, line ->
                                    parseObjectItemBySrc(type, line ?: "", j)
                                }.apply {
                                    if (!this) isSuccess = false
                                }
                            }
                        }
                    }
                }
                if (viewModel.isCancel.value) break

                i.apply {
                    if (isSuccess) {
                        if (appInfoRestore.selectApp.value) appInfoRestore.selectApp.value = false
                        if (appInfoRestore.selectData.value) appInfoRestore.selectData.value = false
                        this.taskState.value = TaskState.Success
                    } else {
                        this.taskState.value = TaskState.Failed
                    }
                    val list = mutableListOf<ProcessObjectItem>()
                    for (j in objectList) {
                        list.add(
                            ProcessObjectItem(
                                state = mutableStateOf(j.state.value),
                                visible = mutableStateOf(j.visible.value),
                                title = mutableStateOf(j.title.value),
                                subtitle = mutableStateOf(j.subtitle.value),
                                type = j.type,
                            )
                        )
                    }
                    this.objectList = list.toList()
                }

                progress.value += 1
                topBarTitle.value =
                    "${context.getString(R.string.restoring)}(${progress.value}/${taskList.size})"

            }

            GsonUtil.saveAppInfoRestoreMapToFile(globalObject.appInfoRestoreMap.value)
            Logcat.getInstance().actionLogAddLine(tag, "Save global map.")
            topBarTitle.value = "${context.getString(R.string.restore_finished)}!"
            allDone.targetState = true
            Logcat.getInstance().actionLogAddLine(tag, "===========${tag}===========")
        }
    }
}
