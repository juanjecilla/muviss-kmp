package com.codingpit.muviss

import android.content.Context
import android.provider.Settings
import com.codingpit.muviss.core.common.motion.SystemMotionPreference

/**
 * Android's reduce-motion signal (#394): "Remove animations" in Settings > Accessibility sets the
 * animator duration scale to 0, which is the only system-wide switch Android has for it. An absent
 * value is the stock 1x, so a device that never touched it reads as "motion is fine".
 */
internal class AndroidSystemMotionPreference(private val context: Context) : SystemMotionPreference {
    override fun reduceMotion(): Boolean = Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
}
