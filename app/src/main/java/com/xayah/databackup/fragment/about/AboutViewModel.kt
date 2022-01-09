package com.xayah.databackup.fragment.about

import android.content.Intent
import android.net.Uri
import android.view.View
import androidx.lifecycle.ViewModel

class AboutViewModel : ViewModel() {
    fun toAppGitHub(v: View) {
        val uri = Uri.parse("https://github.com/XayahSuSuSu/Android-DataBackup")
        val intent = Intent(Intent.ACTION_VIEW, uri)
        v.context.startActivity(intent)
    }

    fun toScriptGitHub(v: View) {
        val uri = Uri.parse("https://github.com/YAWAsau/backup_script")
        val intent = Intent(Intent.ACTION_VIEW, uri)
        v.context.startActivity(intent)
    }

    fun toSimpleScriptGitHub(v: View) {
        val uri = Uri.parse("https://github.com/Petit-Abba/backup_script_zh-CN")
        val intent = Intent(Intent.ACTION_VIEW, uri)
        v.context.startActivity(intent)
    }

    fun toAppAuthorCoolapk(v: View) {
        val uri = Uri.parse("http://www.coolapk.com/u/1394294")
        val intent = Intent(Intent.ACTION_VIEW, uri)
        v.context.startActivity(intent)
    }

    fun toScriptAuthorCoolapk(v: View) {
        val uri = Uri.parse("http://www.coolapk.com/u/2277637")
        val intent = Intent(Intent.ACTION_VIEW, uri)
        v.context.startActivity(intent)
    }
}