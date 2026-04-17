package com.moe.tsunderetranslator

import android.Manifest
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.moe.tsunderetranslator.ui.screens.chat.AsrViewModel
import com.moe.tsunderetranslator.ui.screens.chat.ChatScreen
import com.moe.tsunderetranslator.ui.screens.chat.ChatViewModel
import com.moe.tsunderetranslator.ui.theme.TsundereTranslatorTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    private val viewModel: ChatViewModel by viewModels()
    private val asrViewModel: AsrViewModel by viewModels()

    // 录音权限请求器
    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (!isGranted) {
            Toast.makeText(this, "请打开录音权限以使用语音输入", Toast.LENGTH_SHORT)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 启动时请求权限
        requestPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)

        setContent {
            TsundereTranslatorTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    ChatScreen(viewModel, asrViewModel)
                }
            }
        }
    }
}