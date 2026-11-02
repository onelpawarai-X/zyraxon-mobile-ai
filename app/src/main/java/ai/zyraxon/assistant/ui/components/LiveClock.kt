package ai.zyraxon.assistant.ui.components

import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.InfiniteRepeatableSpec
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.VectorConverter
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos

/**
 * A drop-in replacement for `rememberInfiniteTransition` that keeps moving no
 * matter how the phone's animation settings are set.
 *
 * Compose's own infinite animations take their speed from the system
 * "Animator duration scale". With that set to 0 (Developer options, a battery
 * saver, some ROMs) every such animation is frozen at its first frame — which is
 * exactly "the auras do not move, the spinning thing does not spin". This clock
 * is driven by the display's frame callback instead, so it ignores that setting
 * and the whole interface stays alive.
 *
 * The call sites are unchanged: `transition.animateFloat(initialValue,
 * targetValue, infiniteRepeatable(...))` returns the same kind of `State<Float>`.
 */
class LiveTransition internal constructor(private val nowMs: State<Long>) {

    @Composable
    fun animateFloat(
        initialValue: Float,
        targetValue: Float,
        animationSpec: InfiniteRepeatableSpec<Float>,
        label: String = ""
    ): State<Float> {
        val vec = remember(animationSpec) { animationSpec.animation.vectorize(Float.VectorConverter) }
        val reverse = animationSpec.repeatMode == RepeatMode.Reverse
        return remember(vec, initialValue, targetValue, reverse) {
            val from = AnimationVector1D(initialValue)
            val to = AnimationVector1D(targetValue)
            val zero = AnimationVector1D(0f)
            val duration = vec.durationMillis.coerceAtLeast(1).toLong()
            derivedStateOf {
                val t = nowMs.value
                val play = if (reverse) {
                    val p = t % (duration * 2)
                    if (p >= duration) duration * 2 - p else p
                } else {
                    t % duration
                }
                vec.getValueFromNanos(play * 1_000_000L, from, to, zero).value
            }
        }
    }
}

/** Starts a frame-driven clock for the calling composable; see [LiveTransition]. */
@Composable
fun rememberLiveTransition(label: String = ""): LiveTransition {
    val now = remember { mutableStateOf(0L) }
    // Under a Robolectric unit test the frame clock never stops producing
    // frames, so `waitForIdle()` never returns and every Compose test that
    // touches an animated screen dies with AppNotIdleException. The animations
    // are left exactly as they are on a real device; the loop simply does not
    // start in a unit-test JVM, where it has nothing to prove.
    if (!isUnitTestRuntime()) {
        LaunchedEffect(Unit) {
            var start = -1L
            while (true) {
                withFrameNanos { frame ->
                    if (start < 0) start = frame
                    now.value = (frame - start) / 1_000_000L
                }
            }
        }
    }
    return remember { LiveTransition(now) }
}

/** True only in the Robolectric JVM, which stamps this fingerprint. */
private fun isUnitTestRuntime(): Boolean =
    android.os.Build.FINGERPRINT == "robolectric"
