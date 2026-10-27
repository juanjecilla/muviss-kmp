package com.codingpit.muviss.core.common.platform

/**
 * Coarse description of where the app runs, for the context a Feature Request carries
 * (ADR 0035): enough to triage ("only on iOS 17"), nothing that identifies a device.
 * [name] is one of `android`, `ios`, `desktop`, `web`; [osVersion] is the OS release as
 * the platform reports it, or empty where there is no honest answer (web: a user-agent
 * string is a fingerprint, not a version).
 */
expect val platformName: String

/** See [platformName]. */
expect val platformOsVersion: String
