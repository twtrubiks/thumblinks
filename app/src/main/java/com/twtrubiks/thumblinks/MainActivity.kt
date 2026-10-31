package com.twtrubiks.thumblinks

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        // 系統列透明、圖示深淺跟著系統的深色模式
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent { ThumbLinksApp() }
    }
}
