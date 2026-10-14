package com.sebialex.agent.ui.components

import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.Velocity

/**
 * Swallows the scroll and fling a list leaves over when it reaches its top or bottom, so they
 * never reach a parent: inside a [androidx.compose.material3.ModalBottomSheet] the leftover would
 * otherwise drag the whole sheet (and a fling closes it) mid-gesture, and inside a scrolling page
 * the page would start moving under the finger. The sheet can still be dragged by its handle and
 * by the rows above the list. Pre-scroll is untouched, so the list itself scrolls normally.
 */
private object KeepScrollConnection : NestedScrollConnection {
    override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset = available.copy(x = 0f)
    override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity = available.copy(x = 0f)
}

/** Apply to a scrolling list (on the list itself) so it keeps its own drag; see [KeepScrollConnection]. */
fun Modifier.keepScrollInside(): Modifier = this.nestedScroll(KeepScrollConnection)
