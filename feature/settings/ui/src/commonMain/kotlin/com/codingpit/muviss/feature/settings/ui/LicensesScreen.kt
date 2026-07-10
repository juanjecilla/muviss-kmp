package com.codingpit.muviss.feature.settings.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.codingpit.muviss.feature.settings.domain.OssLicense
import com.codingpit.muviss.feature.settings.domain.ossLicenses
import kotlinx.serialization.Serializable

/** Type-safe navigation route for the OSS licenses list, reached from Settings > About. */
@Serializable
data object LicensesRoute

/** A static, generated list of every OSS dependency this app ships and its license (see `OssLicenses.kt`). */
@Composable
fun LicensesScreen() {
    LazyColumn(Modifier.fillMaxSize().padding(16.dp)) {
        items(ossLicenses, key = { "${it.groupId}:${it.artifactId}" }) { license ->
            LicenseRow(license)
            HorizontalDivider(Modifier.padding(vertical = 4.dp))
        }
    }
}

@Composable
private fun LicenseRow(license: OssLicense) {
    Column(Modifier.padding(vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(license.name, style = MaterialTheme.typography.bodyMedium)
        Text("${license.groupId}:${license.artifactId}", style = MaterialTheme.typography.labelSmall)
        Text(license.licenseName, style = MaterialTheme.typography.bodySmall)
    }
}
