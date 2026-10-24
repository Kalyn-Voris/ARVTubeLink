package com.exarry.arvtubelink.mapping

/**
 * 一帧 VTube Studio 参数集。
 *
 * @param faceFound 本帧是否检测到人脸；false 时 VTS 端忽略注入值。
 * @param parameters VTS 参数名到归一化值。表情类 0..1，眼球方向 -1..1。
 */
data class VtsParams(
    val faceFound: Boolean,
    val parameters: Map<String, Float>
)
