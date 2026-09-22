package com.insta360.kmpsdk.demo.touchscene

sealed interface VoiceCommand {
    data object TakePhoto : VoiceCommand
    data object StartRecording : VoiceCommand
    data object StopRecording : VoiceCommand
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
            "录像", "开始录像", "开始录制" -> VoiceCommand.StartRecording
            "停止录像", "停止录制" -> VoiceCommand.StopRecording
            "画面里有什么", "我面前有什么" -> VoiceCommand.DescribeScene
            "重复", "再说一遍" -> VoiceCommand.Repeat
            "帮助", "有什么命令" -> VoiceCommand.Help
            else -> VoiceCommand.Unknown(raw)
        }
    }
}
