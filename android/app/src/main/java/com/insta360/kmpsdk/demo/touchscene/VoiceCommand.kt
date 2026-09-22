package com.insta360.kmpsdk.demo.touchscene

sealed interface VoiceCommand {
    data object TakePhoto : VoiceCommand
    data object StartStream : VoiceCommand
    data object StopStream : VoiceCommand
    data object FreezeFrame : VoiceCommand
    data object ReturnLive : VoiceCommand
    data object DescribeScene : VoiceCommand
    data object Repeat : VoiceCommand
    data object Help : VoiceCommand
    data class Unknown(val rawText: String) : VoiceCommand
}

object VoiceCommandParser {
    private val punctuation = Regex("[\\s，。！？,.!?]")

    fun parse(raw: String): VoiceCommand {
        val text = raw.lowercase().replace(punctuation, "")
        return when (text) {
            "拍照", "拍摄", "拍一张" -> VoiceCommand.TakePhoto
            // “开始录像”指启动实时视频流，不是 SD 卡录像
            "录像", "开始录像", "开始录制", "开始预览", "开始视频流" -> VoiceCommand.StartStream
            "停止录像", "停止录制", "停止预览", "停止视频流" -> VoiceCommand.StopStream
            "冻结", "冻结画面", "冻结当前画面" -> VoiceCommand.FreezeFrame
            // 语义等同于按下"返回实时"：暂停冻结、恢复视频流
            "恢复视频流", "取消冻结", "解冻", "解冻画面", "暂停冻结", "返回实时", "恢复实时" -> VoiceCommand.ReturnLive
            "画面里有什么", "我面前有什么" -> VoiceCommand.DescribeScene
            "重复", "再说一遍" -> VoiceCommand.Repeat
            "帮助", "有什么命令" -> VoiceCommand.Help
            else -> VoiceCommand.Unknown(raw)
        }
    }
}
