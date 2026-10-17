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
import androidx.compose.animation.core.tween
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
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

    // FIX #8: perf — ONE CompositionLocalProvider wraps the whole tree so every
    // bounceClick reads the current nav-lock value. Re-emitting it inside the Box was
    // redundant (and made the brace structure hard to follow).
    CompositionLocalProvider(LocalNavLock provides vm.isNavigating) {

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
                    // FIX #8: perf — for Menu ↔ Dashboard ↔ CategoryDetail (which mount
                    // LazyVerticalGrids and compute Canvas charts), the slide transition
                    // was allocating hundreds of KB per frame for two full screens. That
                    // triggered a GC pause every ~15 taps, manifesting as a single
                    // stutter mid-slide. For these routes we use a simple 140ms fade —
                    // single incoming layer, minimal allocation, zero GC pressure.
                    //
                    // Other routes (Range, Training, Results) keep the slide since they
                    // are lighter and the slide feels natural there.
                    val heavyScreens = setOf(
                        Screen.Menu, Screen.Progress, Screen.CategoryDetail
                    )
                    val useFade = targetState in heavyScreens || initialState in heavyScreens
                    if (useFade) {
                        fadeIn(tween(140)) togetherWith fadeOut(tween(140))
                    } else {
                        val forward = targetState.ordinal > initialState.ordinal
                        slideInHorizontally(animationSpec = tween(220)) { w ->
                            if (forward) w / 5 else -w / 5
                        } togetherWith
                            slideOutHorizontally(animationSpec = tween(220)) { w ->
                                if (forward) -w / 5 else w / 5
                            }
                    }
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
