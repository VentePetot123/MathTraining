package com.mathmaster.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.core.tween
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

class MainActivity : ComponentActivity() {
    private val vm: TrainerViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MathTheme {
                AppRoot(vm)
            }
        }
    }

    override fun onStop() {
        super.onStop()
        vm.flush()
    }
}

@Composable
fun AppRoot(vm: TrainerViewModel) {
    val colors = AppTheme.colors

    BackHandler(enabled = vm.screen != Screen.Loading && vm.screen != Screen.Menu) {
        vm.onBack()
    }

    // FIX #5: ONE root container with ONE AnimatedContent. There are no fragments, no
    // back stack and no retained views: when a screen leaves composition it is disposed.
    // Every screen paints an opaque full-size background and the container itself is
    // opaque, so no elevation shadow or previous screen can show through on the menu.
    Box(Modifier.fillMaxSize().background(colors.background)) {
        AnimatedContent(
            targetState = vm.screen,
            transitionSpec = {
                val forward = targetState.ordinal > initialState.ordinal
                // FIX #8: perf — shortened from 380ms to 240ms and reduced slide offset
                // so the menu appears before the loading fade completes and card layout
                // settles on the first visible frame.
                (slideInHorizontally(animationSpec = tween(240)) { w -> if (forward) w / 4 else -w / 4 } +
                    fadeIn(tween(240))) togetherWith
                    (slideOutHorizontally(animationSpec = tween(240)) { w -> if (forward) -w / 4 else w / 4 } +
                        fadeOut(tween(180)))
            },
            label = "screens"
        ) { s ->
            when (s) {
                Screen.Loading -> LoadingScreen(vm)
                Screen.Menu -> MenuScreen(vm)
                Screen.Range -> RangeScreen(vm)
                Screen.Training -> TrainingScreen(vm)
                Screen.Results -> ResultsScreen(vm)
                Screen.Progress -> ProgressScreen(vm)
                Screen.CategoryDetail -> CategoryDetailScreen(vm)
            }
        }
    }
}
