package com.example.assignment

import android.net.Uri

data class VideoFile(
    val id: Long,
    val name: String,
    val size: Long,
    val contentUri: Uri
)
