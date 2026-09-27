package com.quickbite.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.activity.viewModels
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.lifecycle.lifecycleScope
import com.quickbite.app.ui.AppScaffold
import com.quickbite.app.ui.AppViewModel
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val vm: AppViewModel by viewModels()
    private val loginLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            vm.onLoginResult(it.data)
        }
    private val logoutLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            vm.onLogoutResult(it.data)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme {
                val state by vm.state.collectAsStateWithLifecycle()
                AppScaffold(
                    state,
                    vm,
                    onLogin = { lifecycleScope.launch { loginLauncher.launch(vm.loginIntent()) } },
                    onLogout = { lifecycleScope.launch { logoutLauncher.launch(vm.logoutIntent()) } }
                )
            }
        }
    }
}