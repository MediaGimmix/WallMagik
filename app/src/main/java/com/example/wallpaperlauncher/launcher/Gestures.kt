package com.example.wallpaperlauncher.launcher

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import com.example.wallpaperlauncher.Gesture
import com.example.wallpaperlauncher.GestureAction
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.max

/**
 * Home-screen background gestures:
 *  - a finished tap reports how many fingers were down ([onTap]); [TapClassifier] turns those into gestures
 *  - one finger held still for [holdMs] starts a hold ([onHold] true, then [onHoldMove], then false on release)
 *  - a vertical swipe ([onSwipe], true = up)
 * Hold positions are fractions (0..1) of the layer's size.
 */
fun Modifier.homeGestures(
    holdMs: Long,
    onTap: (fingers: Int) -> Unit,
    onHold: (active: Boolean, pos: Offset) -> Unit,
    onHoldMove: (pos: Offset) -> Unit,
    onSwipe: (up: Boolean) -> Unit,
): Modifier = pointerInput(Unit) {
    val slop = viewConfiguration.touchSlop
    val swipeDist = 56.dp.toPx()
    fun norm(p: Offset) = Offset(p.x / size.width.coerceAtLeast(1), p.y / size.height.coerceAtLeast(1))

    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        val start = down.position
        var pos = start
        var fingers = 1
        // 1 = moved past slop, 2 = all fingers up; null = still holding when the timer ran out
        val outcome: Int? = withTimeoutOrNull(holdMs) {
            var r = 0
            while (r == 0) {
                val ev = awaitPointerEvent()
                fingers = max(fingers, ev.changes.count { it.pressed })
                ev.changes.firstOrNull { it.id == down.id }?.let { pos = it.position }
                if ((pos - start).getDistance() > slop) r = 1
                else if (ev.changes.none { it.pressed }) r = 2
            }
            r
        }
        when {
            outcome == null && fingers == 1 -> {
                onHold(true, norm(pos))
                while (true) {
                    val ev = awaitPointerEvent()
                    val c = ev.changes.firstOrNull { it.id == down.id }
                    if (c == null || !c.pressed) break
                    pos = c.position
                    c.consume()
                    onHoldMove(norm(pos))
                }
                onHold(false, norm(pos))
            }
            outcome == 2 -> onTap(fingers)
            else -> {
                // Moved, or a multi-finger hold: wait for every finger to lift, then judge the swipe.
                while (true) {
                    val ev = awaitPointerEvent()
                    ev.changes.firstOrNull { it.id == down.id }?.let { pos = it.position }
                    if (ev.changes.none { it.pressed }) break
                }
                val d = pos - start
                if (outcome == 1 && fingers == 1 && abs(d.y) > swipeDist && abs(d.y) > abs(d.x) * 1.2f) {
                    onSwipe(d.y < 0)
                }
            }
        }
    }
}

/**
 * Counts consecutive taps and waits only as long as needed: e.g. a second single-finger tap fires the double tap
 * right away unless a triple tap is configured.
 */
class TapClassifier(
    private val scope: CoroutineScope,
    private val timeoutMs: Long,
    private val actions: () -> Map<Gesture, GestureAction>,
    private val fire: (Gesture) -> Unit,
) {
    private var kind = 0
    private var count = 0
    private var job: Job? = null

    fun tap(fingers: Int) {
        val k = if (fingers >= 2) 2 else 1
        job?.cancel()
        if (k != kind) {
            kind = k
            count = 0
        }
        count++
        val map = actions()
        fun set(g: Gesture) = (map[g] ?: GestureAction.NONE) != GestureAction.NONE
        val more = if (k == 1) {
            (count == 1 && (set(Gesture.DOUBLE_TAP) || set(Gesture.TRIPLE_TAP))) ||
                (count == 2 && set(Gesture.TRIPLE_TAP))
        } else {
            count == 1 && set(Gesture.TWO_FINGER_DOUBLE_TAP)
        }
        if (more) job = scope.launch { delay(timeoutMs); resolve() } else resolve()
    }

    private fun resolve() {
        val g = when (kind to count) {
            1 to 2 -> Gesture.DOUBLE_TAP
            1 to 3 -> Gesture.TRIPLE_TAP
            2 to 1 -> Gesture.TWO_FINGER_TAP
            2 to 2 -> Gesture.TWO_FINGER_DOUBLE_TAP
            else -> null
        }
        kind = 0
        count = 0
        job = null
        g?.let(fire)
    }
}
