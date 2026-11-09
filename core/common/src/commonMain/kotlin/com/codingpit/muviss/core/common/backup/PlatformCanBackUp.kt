package com.codingpit.muviss.core.common.backup

/**
 * Whether this platform's OS backup can carry a Muviss library at all (Device Backup, ADR 0034). Android and iOS
 * do; desktop and the web have no OS backup of app data that Muviss could honour, so Onboarding's backup page and
 * the Settings row are not offered there. Where it is false nothing is excluded either, because nothing is copied.
 */
expect val platformCanBackUp: Boolean
