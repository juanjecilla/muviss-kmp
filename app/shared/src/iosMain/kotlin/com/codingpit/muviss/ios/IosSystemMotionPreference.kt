package com.codingpit.muviss.ios

import com.codingpit.muviss.core.common.motion.SystemMotionPreference
import platform.UIKit.UIAccessibilityIsReduceMotionEnabled

/** iOS's reduce-motion signal (#394): Settings > Accessibility > Motion > Reduce Motion. */
internal object IosSystemMotionPreference : SystemMotionPreference {
    override fun reduceMotion(): Boolean = UIAccessibilityIsReduceMotionEnabled()
}
