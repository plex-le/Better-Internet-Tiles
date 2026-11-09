package be.casperverswijvelt.unifiedinternetqs.settings.settings

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import be.casperverswijvelt.unifiedinternetqs.R
import be.casperverswijvelt.unifiedinternetqs.data.BITPreferences
import be.casperverswijvelt.unifiedinternetqs.settings.IBooleanTileSetting
import be.casperverswijvelt.unifiedinternetqs.tilebehaviour.TileType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

val taskerGuideSetting = object : IBooleanTileSetting {
    override val summaryResource = R.string.tasker_guide_summary

    override val icon = Icons.AutoMirrored.Outlined.OpenInNew
    override val nameResource = R.string.tasker_guide_title
    override val defaultValue = true

    override fun getValue(
        preferences: BITPreferences,
        tileType: TileType?,
    ): Flow<Boolean> {
        return preferences.getTaskerGuide
    }

    override fun setValue(
        preferences: BITPreferences,
        tileType: TileType?,
        coroutineScope: CoroutineScope,
        value: Boolean
    ) {
        coroutineScope.launch {
            preferences.setTaskerGuide(value)
        }
    }
}

