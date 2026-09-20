package com.parkingkok.app.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.parkingkok.app.R
import com.parkingkok.app.theme.ParkingkokTheme
import com.parkingkok.app.theme.spacing
import com.parkingkok.app.ui.components.DetailHeader
import com.parkingkok.app.ui.components.ParkingkokCard
import com.parkingkok.app.ui.components.ParkingkokRow
import com.parkingkok.app.ui.components.ParkingkokScreen
import com.parkingkok.app.ui.components.RowChevron
import com.parkingkok.app.ui.components.SectionTitle
import com.parkingkok.app.ui.components.StatusBadge

/**
 * `05-settings.png`, in the section order docs/19_VISUAL_REFERENCES_AND_UI_MAPPING.md
 * fixes: 자동 감지 -> 알림 -> 권한 -> Plus -> 데이터 -> 개인정보.
 *
 * Rows for things this build does not do are present but disabled and say so. The one
 * case worth naming: the mockup shows `주차 종료 자동 감지` and `배터리 절약 모드` as live switches.
 * Neither exists, so neither is shown — an inert switch invites a tap and then silently
 * ignores it, which is worse than a row that admits it is not ready.
 *
 * The P0 diagnostics screen keeps its route, at the bottom under 개발자.
 */
@Composable
fun SettingsScreen(
    state: SettingsUiState,
    onDetectionEnabledChange: (Boolean) -> Unit,
    onLockScreenNoticeChange: (Boolean) -> Unit,
    onAnalyticsConsentChange: (Boolean) -> Unit,
    onOpenSystemSettings: () -> Unit,
    onOpenBatterySettings: () -> Unit,
    onDeleteHistory: () -> Unit,
    onOpenDiagnostics: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var confirmingDelete by remember { mutableStateOf(false) }

    ParkingkokScreen(
        modifier = modifier,
        header = { DetailHeader(title = stringResource(R.string.settings_title), onBack = onBack) },
    ) {
        item("account") {
            ParkingkokCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        painter = painterResource(R.drawable.ic_person),
                        contentDescription = null,
                        // Neutral like every other glyph on this screen. Blue here bought
                        // nothing: the row is a statement of fact, not an action.
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(24.dp),
                    )
                    Spacer(Modifier.width(MaterialTheme.spacing.medium))
                    Column {
                        Text(
                            text = stringResource(R.string.settings_account_title),
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            text = stringResource(R.string.settings_account_body),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }

        // 1. 자동 감지
        item("detection-title") { SectionTitle(stringResource(R.string.settings_section_detection)) }
        item("detection") {
            ParkingkokCard(contentPadding = 0.dp) {
                SwitchRow(
                    title = stringResource(R.string.settings_detection_toggle),
                    supporting = stringResource(R.string.settings_detection_toggle_caption),
                    iconRes = R.drawable.ic_car,
                    checked = state.detectionEnabled,
                    onCheckedChange = onDetectionEnabledChange,
                )
                SettingsDivider()
                // docs/06 §7b: the lock-screen readout. Its own switch, because a
                // persistent notification nobody can turn off is why people uninstall
                // utilities — and it is on by default because it was asked for by name.
                SwitchRow(
                    title = stringResource(R.string.settings_lock_screen),
                    supporting = stringResource(R.string.settings_lock_screen_caption),
                    iconRes = R.drawable.ic_place,
                    checked = state.lockScreenNoticeEnabled,
                    onCheckedChange = onLockScreenNoticeChange,
                )
                SettingsDivider()
                // docs/07 "동의": off until the user turns it on, and turning it off stops
                // transmission on the next event. The consent change itself is never
                // reported — that would be a transmission decided either side of consent.
                SwitchRow(
                    title = stringResource(R.string.settings_analytics_toggle),
                    supporting = stringResource(R.string.settings_analytics_toggle_caption),
                    iconRes = R.drawable.ic_tune,
                    checked = state.analyticsConsentGranted,
                    onCheckedChange = onAnalyticsConsentChange,
                )
            }
        }
        item("detection-footnote") { SectionFootnote(stringResource(R.string.settings_analytics_footnote)) }

        // 2. 알림
        item("notification-title") {
            SectionTitle(stringResource(R.string.settings_section_notification))
        }
        item("notification") {
            ParkingkokCard(contentPadding = 0.dp) {
                // Not a second permission row. Whether the OS grant exists is stated once,
                // under 권한; this row is about the feature, which is not built yet
                // (candidate notifications land with detection).
                ParkingkokRow(
                    title = stringResource(R.string.settings_notification_status),
                    supporting = stringResource(R.string.settings_notification_pending_caption),
                    iconRes = R.drawable.ic_bell,
                    enabled = false,
                    trailing = {
                        StatusBadge(
                            text = stringResource(R.string.coming_soon_badge),
                            containerColor = MaterialTheme.colorScheme.surfaceVariant,
                            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    },
                )
            }
        }

        // 3. 권한
        item("permission-title") {
            SectionTitle(stringResource(R.string.settings_section_permission))
        }
        item("permission") {
            ParkingkokCard(contentPadding = 0.dp) {
                PermissionRow(
                    iconRes = R.drawable.ic_place,
                    title = stringResource(R.string.settings_permission_location),
                    supporting = stringResource(R.string.settings_permission_location_caption),
                    granted = state.foregroundLocationGranted,
                    onClick = onOpenSystemSettings,
                )
                SettingsDivider()
                PermissionRow(
                    iconRes = R.drawable.ic_map,
                    title = stringResource(R.string.settings_permission_background),
                    supporting = stringResource(R.string.settings_permission_background_caption),
                    granted = state.backgroundLocationGranted,
                    onClick = onOpenSystemSettings,
                )
                SettingsDivider()
                // docs/04_ANDROID §4b. Not a runtime permission, but it belongs here
                // because it fails the same way the others do: without it the drive
                // capture cannot be raised in the background and the app detects nothing.
                PermissionRow(
                    iconRes = R.drawable.ic_shield,
                    title = stringResource(R.string.settings_permission_battery),
                    supporting = stringResource(R.string.settings_permission_battery_caption),
                    granted = state.batteryUnrestricted,
                    onClick = onOpenBatterySettings,
                )
                SettingsDivider()
                PermissionRow(
                    iconRes = R.drawable.ic_person,
                    title = stringResource(R.string.settings_permission_activity),
                    supporting = stringResource(R.string.settings_permission_activity_caption),
                    granted = state.activityRecognitionGranted,
                    onClick = onOpenSystemSettings,
                )
                SettingsDivider()
                PermissionRow(
                    iconRes = R.drawable.ic_bell,
                    title = stringResource(R.string.settings_permission_notification),
                    supporting = stringResource(R.string.settings_permission_notification_caption),
                    granted = state.notificationsEnabled,
                    onClick = onOpenSystemSettings,
                )
            }
        }

        // 4. Plus
        item("plus-title") { SectionTitle(stringResource(R.string.settings_section_plus)) }
        item("plus") {
            ParkingkokCard(contentPadding = 0.dp) {
                ParkingkokRow(
                    title = stringResource(R.string.settings_plus_title),
                    supporting = stringResource(R.string.settings_plus_caption),
                    // No sparkle. docs/10 §9 rules sparkles and crowns out of anything
                    // that talks about Plus, and a row that says 준비 중 needs no glyph.
                    iconRes = null,
                    enabled = false,
                    trailing = {
                        StatusBadge(
                            text = stringResource(R.string.coming_soon_badge),
                            containerColor = MaterialTheme.colorScheme.surfaceVariant,
                            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    },
                )
            }
        }

        // 5. 데이터
        item("data-title") { SectionTitle(stringResource(R.string.settings_section_data)) }
        item("data") {
            ParkingkokCard(contentPadding = 0.dp) {
                ParkingkokRow(
                    title = stringResource(R.string.settings_data_export),
                    supporting = stringResource(R.string.settings_data_export_caption),
                    iconRes = R.drawable.ic_download,
                    enabled = false,
                    trailing = {
                        StatusBadge(
                            text = stringResource(R.string.coming_soon_badge),
                            containerColor = MaterialTheme.colorScheme.surfaceVariant,
                            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    },
                )
                SettingsDivider()
                ParkingkokRow(
                    title = stringResource(R.string.settings_data_delete),
                    supporting = stringResource(R.string.settings_data_delete_caption),
                    iconRes = R.drawable.ic_delete,
                    iconContainerColor = MaterialTheme.colorScheme.errorContainer,
                    iconContentColor = MaterialTheme.colorScheme.onErrorContainer,
                    onClick = { confirmingDelete = true },
                    trailing = { RowChevron() },
                )
            }
        }

        // 6. 개인정보
        item("privacy-title") { SectionTitle(stringResource(R.string.settings_section_privacy)) }
        item("privacy") {
            ParkingkokCard {
                Row {
                    Icon(
                        painter = painterResource(R.drawable.ic_shield),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp),
                    )
                    Spacer(Modifier.width(MaterialTheme.spacing.medium))
                    Text(
                        text = stringResource(R.string.settings_privacy_body),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        // The P0 instrumentation screen keeps its access path (CLAUDE.md Development Order).
        item("developer-title") {
            SectionTitle(stringResource(R.string.settings_section_developer))
        }
        item("developer") {
            ParkingkokCard(contentPadding = 0.dp) {
                ParkingkokRow(
                    title = stringResource(R.string.settings_diagnostics),
                    supporting = stringResource(R.string.settings_diagnostics_caption),
                    iconRes = R.drawable.ic_tune,
                    onClick = onOpenDiagnostics,
                    trailing = { RowChevron() },
                )
            }
        }
    }

    if (confirmingDelete) {
        AlertDialog(
            onDismissRequest = { confirmingDelete = false },
            title = { Text(stringResource(R.string.history_delete_all_confirm_title)) },
            text = { Text(stringResource(R.string.history_delete_all_confirm_body)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmingDelete = false
                        onDeleteHistory()
                    },
                ) {
                    Text(
                        text = stringResource(R.string.action_delete),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmingDelete = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }
}

/**
 * A row whose whole purpose is one switch.
 *
 * The word beside the switch is what makes the state readable without colour
 * (docs/01_PRODUCT_REQUIREMENTS.md §8), and tapping the row is the same intent as moving
 * the switch — a caption two lines tall is a bigger target than the thumb.
 */
@Composable
private fun SwitchRow(
    title: String,
    supporting: String,
    iconRes: Int,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    ParkingkokRow(
        title = title,
        supporting = supporting,
        iconRes = iconRes,
        onClick = { onCheckedChange(!checked) },
        trailing = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(
                        if (checked) R.string.settings_detection_on else R.string.settings_detection_off,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.width(MaterialTheme.spacing.small))
                Switch(checked = checked, onCheckedChange = onCheckedChange)
            }
        },
    )
}

/**
 * Small print under a card. Used for docs/09 §13's sentence, which has to sit with the
 * switch it qualifies rather than in the 개인정보 section further down.
 */
@Composable
private fun SectionFootnote(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = MaterialTheme.spacing.large),
    )
}

/**
 * A permission row. The badge says `허용됨` or `거부됨` in words, so the grant state does not
 * depend on the badge's colour (docs/01_PRODUCT_REQUIREMENTS.md §8).
 */
@Composable
private fun PermissionRow(
    iconRes: Int,
    title: String,
    supporting: String,
    granted: Boolean,
    onClick: () -> Unit,
) {
    ParkingkokRow(
        title = title,
        supporting = supporting,
        iconRes = iconRes,
        // The chip used to go mint when the permission was granted, saying in colour what
        // the badge beside it already says in a word. The badge carries the state; the
        // chip takes the neutral default.
        onClick = onClick,
        trailing = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                StatusBadge(
                    text = stringResource(
                        if (granted) {
                            R.string.settings_permission_granted
                        } else {
                            R.string.settings_permission_denied
                        },
                    ),
                    containerColor = if (granted) {
                        MaterialTheme.colorScheme.tertiaryContainer
                    } else {
                        MaterialTheme.colorScheme.surfaceVariant
                    },
                    contentColor = if (granted) {
                        MaterialTheme.colorScheme.onTertiaryContainer
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
                Spacer(Modifier.width(MaterialTheme.spacing.tiny))
                RowChevron()
            }
        },
    )
}

@Composable
private fun SettingsDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(start = MaterialTheme.spacing.large),
        color = MaterialTheme.colorScheme.outlineVariant,
    )
}

@Preview(name = "Settings", showBackground = true)
@Composable
private fun SettingsPreview() {
    ParkingkokTheme {
        SettingsScreen(
            state = SettingsUiState(
                detectionEnabled = true,
                activityRecognitionGranted = true,
                foregroundLocationGranted = true,
                backgroundLocationGranted = false,
                notificationsEnabled = true,
                analyticsConsentGranted = false,
            ),
            onDetectionEnabledChange = {},
            onLockScreenNoticeChange = {},
            onAnalyticsConsentChange = {},
            onOpenSystemSettings = {},
            onOpenBatterySettings = {},
            onDeleteHistory = {},
            onOpenDiagnostics = {},
            onBack = {},
        )
    }
}
