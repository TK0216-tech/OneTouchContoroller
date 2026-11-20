package jp.relative.tech.one_touch_controller.domain.config

fun interface StringTranslator {
    fun tr(text: String, vararg values: Any?): String
}
