package com.exarry.arvtubelink.mapping

/** 一帧 VTube Studio 参数集；faceFound=false 时 VTS 端忽略注入值 */
data class VtsParams(
    val faceFound: Boolean,
    val parameters: Map<String, Float>
)
