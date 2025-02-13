package com.xayah.librootservice.reflection

import android.annotation.SuppressLint
import android.os.MemoryFile
import org.lsposed.hiddenapibypass.HiddenApiBypass
import java.io.FileDescriptor

@SuppressLint("NewApi")
class MemoryFileHidden {
    companion object {
        fun getFileDescriptor(memoryFile: MemoryFile): FileDescriptor {
            return HiddenApiBypass.invoke(
                MemoryFile::class.java,
                memoryFile,
                "getFileDescriptor"
            ) as FileDescriptor
        }
    }
}
