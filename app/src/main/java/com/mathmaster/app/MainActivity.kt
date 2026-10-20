package com.mathmaster.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.animation.core.tween
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.remember
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier

class MainActivity : ComponentActivity() {
    private val vm: TrainerViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MathTheme(themeMode = vm.themeMode) {
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

    val navLock = remember(vm) { { vm.isNavigating } }

    // FIX #8: perf — ONE CompositionLocalProvider wraps the whole tree ...
    CompositionLocalProvider(LocalNavLock provides navLock) {

        // FIX #8: perf — release the nav lock after the destination screen has composed
        // and drawn. Keyed on vm.screen so it fires exactly once per transition.
        LaunchedEffect(vm.screen) {
            if (vm.screen == Screen.Loading) return@LaunchedEffect
            // Snapshot the generation BEFORE yielding — if a nav races in during the
            // settle frames, this release would otherwise carry the wrong generation.
            val gen = vm.currentNavGeneration()
            withFrameNanos { }
            withFrameNanos { }
            vm.arrivedAt(gen)
        }

        BackHandler(enabled = vm.screen != Screen.Loading && vm.screen != Screen.Menu) {
            vm.onBack()
        }

        // FIX #5: ONE root container with ONE AnimatedContent. There are no fragments,
        // no back stack, and no retained views: when a screen leaves composition it is
        // disposed. Every screen paints an opaque full-size background and the container
        // itself is opaque, so no elevation shadow or previous screen can show through.
        Box(Modifier.fillMaxSize().background(colors.background)) {
            AnimatedContent(
                targetState = vm.screen,
                transitionSpec = {
                    // FIX #8: perf — zoom-fade transition. Both `scale` and `alpha` are
                    // pure graphicsLayer properties → rendered on the RenderThread, NOT the
                    // main thread. This means the transition can NEVER stutter, even if
                    // the destination screen is doing heavy layout on its first frame.
                    //
                    // The zoom is subtle (0.94 → 1.0) so it reads as "the new screen is
                    // coming toward you", not "it's popping". The fade is quick (200ms) so
                    // it feels responsive. This is what Google Maps / Instagram use.
                    val enter = scaleIn(
                        animationSpec = tween(220, easing = FastOutSlowInEasing),
                        initialScale = 0.94f,
                        transformOrigin = TransformOrigin.Center
                    ) + fadeIn(tween(220))
                    val exit = scaleOut(
                        animationSpec = tween(220, easing = FastOutSlowInEasing),
                        targetScale = 1.04f,
                        transformOrigin = TransformOrigin.Center
                    ) + fadeOut(tween(180))
                    enter togetherWith exit
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

    }   // closes CompositionLocalProvider(LocalNavLock)
}
