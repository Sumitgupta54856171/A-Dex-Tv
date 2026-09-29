package com.desktopmode.tv.model

data class InputEvent(
    val type: String,
    val action: Int? = null,
    val keyCode: Int? = null,
    val metaState: Int? = null,
    val x: Float? = null,
    val y: Float? = null,
    val button: Int? = null,
    val scrollX: Float? = null,
    val scrollY: Float? = null
)
