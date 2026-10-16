package io.github.cubsync.service

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The arithmetic the interface's own numbers are made of.
 *
 * The gestures that feed [ItemScale.zoomBy] cannot be checked from here — Compose Desktop
 * reports a wheel through `onPointerEvent` and the phone reports a pinch through a pointer
 * event loop, and neither is reachable from a unit test. The arithmetic is reachable, and
 * it is where a wrong answer would be invisible: a pinch that drifts, or a value that walks
 * out of its range and stays there.
 */
class UiSpecTest {

    @Test
    fun a_pinch_scales_by_its_own_factor() {
        assertEquals(105, ItemScale.zoomBy(100, 1.05f))
        assertEquals(95, ItemScale.zoomBy(100, 0.95f))
        assertEquals(100, ItemScale.zoomBy(100, 1f), "a pinch that did not move changes nothing")
    }

    @Test
    fun pinches_accumulate_instead_of_rounding_away() {
        // Six small pinches of a per-cent each have to add up to something visible. This is
        // why the arithmetic is multiplicative: a gesture is a proportion, not a count.
        var scale = ItemScale.DEFAULT
        repeat(10) { scale = ItemScale.zoomBy(scale, 1.01f) }
        assertEquals(110, scale)
    }

    @Test
    fun the_range_holds_at_both_ends() {
        assertEquals(ItemScale.MIN, ItemScale.zoomBy(ItemScale.MIN, 0.5f))
        assertEquals(ItemScale.MAX, ItemScale.zoomBy(ItemScale.MAX, 4f))
        assertEquals(ItemScale.MAX, ItemScale.zoomBy(190, 4f), "and it does not overshoot")
    }

    @Test
    fun a_nonsense_factor_leaves_the_value_alone() {
        // A gesture library that divides by a size of zero can hand over a zero, a negative
        // or a NaN. None of those is a reason to resize somebody's rows.
        assertEquals(120, ItemScale.zoomBy(120, 0f))
        assertEquals(120, ItemScale.zoomBy(120, -1f))
        assertEquals(120, ItemScale.zoomBy(120, Float.NaN))
        assertEquals(120, ItemScale.zoomBy(120, Float.POSITIVE_INFINITY))
    }

    @Test
    fun the_panel_and_the_gesture_agree_on_where_the_ends_are() {
        // The panel steps by STEP and clamps; the gesture clamps. If they disagreed, the
        // value a person sets with one control would move under the other.
        assertEquals(ItemScale.MAX, ItemScale.clamp(ItemScale.MAX + ItemScale.STEP * 3))
        assertEquals(ItemScale.MIN, ItemScale.clamp(ItemScale.MIN - ItemScale.STEP * 3))
    }
}
