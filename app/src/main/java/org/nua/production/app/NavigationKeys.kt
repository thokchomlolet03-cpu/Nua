package org.nua.production.app

import androidx.navigation3.runtime.NavKey
import kotlinx.serialization.Serializable

@Serializable data object Splash : NavKey
@Serializable data object Main : NavKey
@Serializable data object Setup : NavKey
@Serializable data class Player(val videoPath: String) : NavKey

