package io.github.razvanmanolache.herdronthego

import android.annotation.SuppressLint
import android.content.SharedPreferences

/** KTX edit returns Unit; security-sensitive writes must check commit's result. */
@SuppressLint("UseKtx")
internal inline fun SharedPreferences.commitEdit(block: SharedPreferences.Editor.() -> Unit): Boolean =
    edit().apply(block).commit()
