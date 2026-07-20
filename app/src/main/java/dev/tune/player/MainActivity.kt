package dev.tune.player

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.tune.player.data.CoverMode
import dev.tune.player.ui.MainViewModel
import dev.tune.player.ui.components.ArtworkStyle
import dev.tune.player.ui.components.LocalArtworkStyle
import dev.tune.player.ui.TuneApp
import dev.tune.player.ui.theme.TuneTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val vm: MainViewModel = viewModel()
            val themeMode by vm.themeMode.collectAsState()
            val blackTheme by vm.blackTheme.collectAsState()
            val dynamicColor by vm.dynamicColor.collectAsState()

            val coverMode by vm.coverMode.collectAsState()
            val squareCovers by vm.squareCovers.collectAsState()
            val roundedCorners by vm.roundedCorners.collectAsState()

            TuneTheme(themeMode = themeMode, blackTheme = blackTheme, dynamicColor = dynamicColor) {
                CompositionLocalProvider(
                    LocalArtworkStyle provides ArtworkStyle(
                        showCovers = coverMode != CoverMode.OFF,
                        squareCovers = squareCovers,
                        roundedCorners = roundedCorners,
                    )
                ) {
                    Surface(
                        modifier = Modifier.fillMaxSize(),
                        color = MaterialTheme.colorScheme.background,
                    ) {
                        PermissionGate(vm) { TuneApp(vm) }
                    }
                }
            }
        }
    }
}

/**
 * The library can't be read without audio permission, so the app asks once on launch and shows a
 * retry affordance if the user declines.
 */
@Composable
private fun PermissionGate(vm: MainViewModel, content: @Composable () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val granted by vm.permissionGranted.collectAsState()

    val permissions = buildList {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(Manifest.permission.READ_MEDIA_AUDIO)
            add(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            add(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
    }

    val audioPermission =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) Manifest.permission.READ_MEDIA_AUDIO
        else Manifest.permission.READ_EXTERNAL_STORAGE

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        // Notifications are optional; only the audio grant gates the library.
        if (results[audioPermission] == true) vm.onPermissionGranted()
    }

    LaunchedEffect(Unit) {
        val alreadyGranted = ContextCompat.checkSelfPermission(context, audioPermission) ==
            PackageManager.PERMISSION_GRANTED
        if (alreadyGranted) vm.onPermissionGranted()
        else launcher.launch(permissions.toTypedArray())
    }

    if (granted) {
        content()
    } else {
        Column(
            modifier = Modifier.fillMaxSize().padding(32.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = "Tune needs permission to read the music on this device.",
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center,
            )
            Button(
                onClick = { launcher.launch(permissions.toTypedArray()) },
                modifier = Modifier.padding(top = 16.dp),
            ) { Text("Grant permission") }
        }
    }
}
