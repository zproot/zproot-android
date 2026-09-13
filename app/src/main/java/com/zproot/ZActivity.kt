package com.zproot

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class ZActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    Screen()
                }
            }
        }
    }
}

@Composable
fun Screen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var output by remember { mutableStateOf("") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Button(
            onClick = { output = "" },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Clear")
        }

        Button(
            onClick = {
                scope.launch {
                    output = "Installing Alpine...\n"
                    val lines = mutableListOf<String>()
                    withContext(Dispatchers.IO) {
                        RootfsManager.install(context) { line ->
                            lines.add(line)
                        }
                    }
                    output += lines.joinToString("\n")
                    output += "\nDone."
                }
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Install Alpine")
        }

        Button(
            onClick = {
                scope.launch {
                    output = withContext(Dispatchers.IO) { runSh(context) }
                }
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Run sh")
        }

        Text(
            text = output,
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
            style = MaterialTheme.typography.bodySmall
        )
    }
}

fun runSh(context: Context): String {
    return try {
        val bin = File(context.applicationInfo.nativeLibraryDir, "libzproot.so")
        val rootfs = RootfsManager.rootfsDir(context).absolutePath

        val pb = ProcessBuilder(
            bin.absolutePath,
            "--rootfs", rootfs,
            "/bin/sh", "-c", "echo hello; uname -a; ls /",
        )
        pb.redirectErrorStream(true)
        val proc = pb.start()
        val text = proc.inputStream.bufferedReader().readText()
        val code = proc.waitFor()
        "exit=$code\n$text"
    } catch (e: Exception) {
        "error: ${e.javaClass.simpleName}: ${e.message}"
    }
}
