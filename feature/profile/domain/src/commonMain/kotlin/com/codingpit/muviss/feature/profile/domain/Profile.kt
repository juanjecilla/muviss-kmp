package com.codingpit.muviss.feature.profile.domain

/**
 * The user's local identity: a display name and one of [AvatarPresets]. Purely
 * local (no accounts yet) — EPIC 9's sync engine will attach a remote id to
 * this same profile rather than replacing it.
 */
data class LocalProfile(
    val displayName: String,
    val avatarId: String,
) {
    val avatar: AvatarPreset get() = AvatarPresets.byId(avatarId)

    companion object {
        val DEFAULT = LocalProfile(displayName = "You", avatarId = AvatarPresets.default.id)
    }
}

/**
 * One bundled avatar choice: a flat color, rendered as a circle with the
 * display name's initial in the UI (see `feature/profile/ui`'s `AvatarBadge`)
 * — no image assets needed, per EPIC 4's scope.
 */
data class AvatarPreset(
    val id: String,
    val colorArgb: Long,
)

/** The fixed set of avatar presets a user can pick from. */
object AvatarPresets {
    val default = AvatarPreset(id = "indigo", colorArgb = 0xFF6C5CE7)

    val all: List<AvatarPreset> = listOf(
        default,
        AvatarPreset(id = "teal", colorArgb = 0xFF00B894),
        AvatarPreset(id = "rose", colorArgb = 0xFFE84393),
        AvatarPreset(id = "amber", colorArgb = 0xFFE1A100),
        AvatarPreset(id = "sky", colorArgb = 0xFF0984E3),
        AvatarPreset(id = "slate", colorArgb = 0xFF636E72),
    )

    fun byId(id: String): AvatarPreset = all.firstOrNull { it.id == id } ?: default
}
