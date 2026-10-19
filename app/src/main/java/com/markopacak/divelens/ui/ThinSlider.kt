package com.markopacak.divelens.ui

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.height
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderColors
import androidx.compose.material3.SliderDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp

/**
 * A Material slider with a 4 dp track and a slim thumb, so it covers less of the photo or video behind it. The touch
 * target stays the slider's full height.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ThinSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    onValueChangeFinished: (() -> Unit)? = null,
    colors: SliderColors = SliderDefaults.colors(),
) {
    val interaction = remember { MutableInteractionSource() }
    Slider(
        value = value,
        onValueChange = onValueChange,
        onValueChangeFinished = onValueChangeFinished,
        colors = colors,
        interactionSource = interaction,
        thumb = { SliderDefaults.Thumb(interactionSource = interaction, colors = colors, thumbSize = DpSize(4.dp, 20.dp)) },
        track = { state ->
            SliderDefaults.Track(
                sliderState = state,
                colors = colors,
                modifier = Modifier.height(4.dp),
                drawStopIndicator = null,
                thumbTrackGapSize = 2.dp,
            )
        },
        modifier = modifier,
    )
}
