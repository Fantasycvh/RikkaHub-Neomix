package app.androidrik.rikkahub.utils

import android.content.ClipData

fun ClipData.getText(): String {
    return buildString {
        repeat(itemCount) {
            append(getItemAt(it).text ?: "")
        }
    }
}
