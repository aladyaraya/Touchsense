package com.insta360.kmpsdk.demo.touchscene

/** Conservative spoken vocabulary: unknown English labels never leak into Chinese TTS. */
internal object SceneLabelVocabulary {
    private val chinese = mapOf(
        "person" to "人",
        "people" to "人物",
        "face" to "人脸",
        "car" to "汽车",
        "vehicle" to "车辆",
        "bicycle" to "自行车",
        "tree" to "树",
        "plant" to "植物",
        "flower" to "花",
        "dog" to "狗",
        "cat" to "猫",
        "food" to "食物",
        "building" to "建筑",
        "sky" to "天空",
        "water" to "水面",
        "road" to "道路",
        "furniture" to "家具",
        "table" to "桌子",
        "cup" to "杯子",
    )
    private val chineseOnly = Regex("^[\\p{IsHan}]{1,12}$")

    fun translate(label: String): String? {
        val trimmed = label.trim()
        return chinese[trimmed.lowercase()] ?: trimmed.takeIf { chineseOnly.matches(it) }
    }

    /** Input is already ordered by confidence; ignore unsupported labels before taking three. */
    fun topNames(labelsByConfidence: List<String>): List<String> =
        labelsByConfidence.mapNotNull(::translate).distinct().take(3)
}
