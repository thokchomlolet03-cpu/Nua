package com.example.nua

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.ui.NavDisplay
import com.example.nua.ui.main.MainScreen
import com.example.nua.ui.main.MainScreenViewModel
import com.example.nua.ui.setup.SetupScreen
import com.example.nua.ui.player.PlayerScreen

@Composable
fun MainNavigation() {
  val backStack = rememberNavBackStack(Main)
  val sharedViewModel: MainScreenViewModel = viewModel()

  NavDisplay(
    backStack = backStack,
    onBack = { backStack.removeLastOrNull() },
    entryProvider =
      entryProvider {
        entry<Main> {
          MainScreen(
            onItemClick = { navKey -> backStack.add(navKey) },
            viewModel = sharedViewModel,
            modifier = Modifier.fillMaxSize()
          )
        }
        entry<Setup> {
          SetupScreen(
            viewModel = sharedViewModel,
            onBack = { backStack.removeLastOrNull() }
          )
        }
        entry<Player> { key ->
          PlayerScreen(
            videoPath = key.videoPath,
            onBack = { backStack.removeLastOrNull() }
          )
        }
      },
  )
}

