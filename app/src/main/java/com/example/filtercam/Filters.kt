package com.example.filtercam

import android.graphics.ColorMatrix

enum class CamFilter(val label: String, val matrix: ColorMatrix) {
    NORMAL("Normal", ColorMatrix()),
    MONO("B&W", ColorMatrix().apply { setSaturation(0f) }),
    SEPIA(
        "Sepia", ColorMatrix(
            floatArrayOf(
                0.393f, 0.769f, 0.189f, 0f, 0f,
                0.349f, 0.686f, 0.168f, 0f, 0f,
                0.272f, 0.534f, 0.131f, 0f, 0f,
                0f, 0f, 0f, 1f, 0f
            )
        )
    ),
    VIVID("Vivid", ColorMatrix().apply { setSaturation(1.8f) }),
    COOL("Cool", ColorMatrix().apply { setScale(0.9f, 1.0f, 1.2f, 1f) }),
    WARM("Warm", ColorMatrix().apply { setScale(1.2f, 1.0f, 0.85f, 1f) }),
    VINTAGE("Vintage", ColorMatrix().apply {
        setSaturation(0.6f)
        postConcat(ColorMatrix().apply { setScale(1.1f, 1.0f, 0.85f, 1f) })
        // slight contrast lift + faded blacks
        postConcat(ColorMatrix(floatArrayOf(
            0.9f, 0f, 0f, 0f, 20f,
            0f, 0.9f, 0f, 0f, 20f,
            0f, 0f, 0.9f, 0f, 20f,
            0f, 0f, 0f, 1f, 0f
        )))
    }),
    INVERT(
        "Invert", ColorMatrix(
            floatArrayOf(
                -1f, 0f, 0f, 0f, 255f,
                0f, -1f, 0f, 0f, 255f,
                0f, 0f, -1f, 0f, 255f,
                0f, 0f, 0f, 1f, 0f
            )
        )
    )
}
