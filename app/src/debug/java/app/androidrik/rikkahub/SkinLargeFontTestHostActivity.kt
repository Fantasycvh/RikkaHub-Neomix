package app.androidrik.rikkahub

import android.content.Context
import android.content.res.Configuration

/** Activity-local font configuration also reaches Dialog/BottomSheet windows; no system setting changes. */
class SkinLargeFontTestHostActivity : SketchTestHostActivity() {
    override fun attachBaseContext(newBase: Context) {
        val configuration = Configuration(newBase.resources.configuration).apply { fontScale = 2f }
        super.attachBaseContext(newBase.createConfigurationContext(configuration))
    }
}
