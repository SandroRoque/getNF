package io.github.sandroroque.getnf.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import io.github.sandroroque.getnf.GetNFApp
import io.github.sandroroque.getnf.data.ServerConfig
import io.github.sandroroque.getnf.upload.UploadWorker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onDone: () -> Unit) {
  val context = LocalContext.current
  val app = context.applicationContext as GetNFApp
  val scope = rememberCoroutineScope()
  val initial = remember { app.serverSettings.get() }
  var url by remember { mutableStateOf(initial.url) }
  var email by remember { mutableStateOf(initial.email) }
  var password by remember { mutableStateOf(initial.password) }
  var result by remember { mutableStateOf<String?>(null) }

  Scaffold(topBar = { TopAppBar(title = { Text("PocketBase server") }) }) { padding ->
    Column(Modifier.fillMaxSize().padding(padding).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
      OutlinedTextField(
        url,
        { url = it },
        label = { Text("Server URL") },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
        modifier = Modifier.fillMaxWidth(),
      )
      OutlinedTextField(
        email,
        { email = it },
        label = { Text("Email") },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
        modifier = Modifier.fillMaxWidth(),
      )
      OutlinedTextField(
        password,
        { password = it },
        label = { Text("Password") },
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        modifier = Modifier.fillMaxWidth(),
      )
      Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedButton(
          onClick = {
            result = "Testing…"
            scope.launch {
              result =
                withContext(Dispatchers.IO) {
                  runCatching { app.pocketBase.login(ServerConfig(url.trim(), email.trim(), password)) }
                    .fold({ "Login OK ✓" }, { "Failed: ${it.message}" })
                }
            }
          }
        ) {
          Text("Test")
        }
        Button(
          onClick = {
            app.serverSettings.set(ServerConfig(url, email, password))
            UploadWorker.enqueue(context)
            onDone()
          }
        ) {
          Text("Save")
        }
      }
      result?.let { Text(it) }
    }
  }
}
