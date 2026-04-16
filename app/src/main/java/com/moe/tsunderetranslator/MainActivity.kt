package com.moe.tsunderetranslator

import android.Manifest
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.moe.tsunderetranslator.ui.theme.TsundereTranslatorTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    private val viewModel: AsrViewModel by viewModels()

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
                    AsrScreen(viewModel)
                }
            }
        }
    }
}

@Composable
fun AsrScreen(viewModel: AsrViewModel) {
    // 观察 ASR 结果文本
    val asrText by viewModel.uiText.collectAsState()
    var isRecording by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        // 对应文本显示区域 (替代传统的 TextView)
        Text(
            text = if (asrText.isEmpty()) "点击下方按钮开始说话" else asrText,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(bottom = 16.dp)
        )

        // 控制按钮
        Button(
            onClick = {
                viewModel.toggleAsr(isRecording)
                isRecording = !isRecording
            },
            modifier = Modifier.height(56.dp)
        ) {
            Text(if (isRecording) "停止识别" else "开始语音识别")
        }
    }
}