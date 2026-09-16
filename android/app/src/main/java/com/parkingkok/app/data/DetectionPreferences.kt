package com.parkingkok.app.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore

/**
 * Single DataStore instance for all detection state
 * (docs/04_ANDROID_IMPLEMENTATION.md §11: DataStore holds settings, registration desired
 * state, and the lightweight detection snapshot; Room is reserved for parking history).
 *
 * Keeping checkpoint, registration record, and event log in one store means each
 * `edit {}` is a single atomic file replacement — a torn write cannot leave the
 * checkpoint newer than the registration record.
 */
private const val DETECTION_STORE_NAME = "detection_state"

private val Context.detectionPreferences: DataStore<Preferences> by
    preferencesDataStore(name = DETECTION_STORE_NAME)

fun detectionDataStore(context: Context): DataStore<Preferences> =
    context.applicationContext.detectionPreferences
