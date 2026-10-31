package com.twtrubiks.thumblinks

import android.app.Application
import android.content.Context
import java.io.File

class ThumbLinksApplication : Application() {
    val container by lazy { AppContainer(this) }
}

val Context.container: AppContainer get() = (applicationContext as ThumbLinksApplication).container

/** 整個 App 共用的東西：連結清單、上傳縮圖的資料夾。 */
class AppContainer(context: Context) {
    val thumbsDir = File(context.filesDir, "thumbs")

    val links: LinkStore by lazy { LinkStore(AndroidSql(context), thumbsDir) }
}
