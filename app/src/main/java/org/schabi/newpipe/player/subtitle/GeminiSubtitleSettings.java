/*
 * SPDX-FileCopyrightText: 2026 NewPipe contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.schabi.newpipe.player.subtitle;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.preference.PreferenceManager;

import org.schabi.newpipe.R;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * Stores the Gemini API key outside preferences so backups do not include it.
 */
public final class GeminiSubtitleSettings {
    private static final String TAG = GeminiSubtitleSettings.class.getSimpleName();
    private static final String API_KEY_FILE_NAME = "gemini-subtitle-api-key";

    private GeminiSubtitleSettings() { }

    @NonNull
    public static String getApiKey(@NonNull final Context context) {
        migrateLegacyApiKey(context);
        final File apiKeyFile = getApiKeyFile(context);
        if (!apiKeyFile.isFile()) {
            return "";
        }

        try (FileInputStream inputStream = new FileInputStream(apiKeyFile)) {
            final ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
            final byte[] buffer = new byte[1024];
            int bytesRead;
            while ((bytesRead = inputStream.read(buffer)) != -1) {
                outputStream.write(buffer, 0, bytesRead);
            }
            return outputStream.toString(StandardCharsets.UTF_8.name());
        } catch (final IOException e) {
            Log.e(TAG, "Could not read Gemini API key", e);
            return "";
        }
    }

    public static boolean setApiKey(@NonNull final Context context,
                                    @NonNull final String apiKey) {
        final File apiKeyFile = getApiKeyFile(context);
        if (apiKey.isBlank()) {
            return !apiKeyFile.exists() || apiKeyFile.delete();
        }

        try (FileOutputStream outputStream = new FileOutputStream(apiKeyFile)) {
            outputStream.write(apiKey.trim().getBytes(StandardCharsets.UTF_8));
            return true;
        } catch (final IOException e) {
            Log.e(TAG, "Could not store Gemini API key", e);
            return false;
        }
    }

    private static void migrateLegacyApiKey(@NonNull final Context context) {
        final SharedPreferences preferences =
                PreferenceManager.getDefaultSharedPreferences(context);
        final String preferenceKey = context.getString(R.string.gemini_subtitle_api_key_key);
        final String legacyApiKey = preferences.getString(preferenceKey, "");
        if (legacyApiKey != null && !legacyApiKey.isBlank() && setApiKey(context, legacyApiKey)) {
            preferences.edit().remove(preferenceKey).apply();
        }
    }

    @NonNull
    private static File getApiKeyFile(@NonNull final Context context) {
        return new File(context.getNoBackupFilesDir(), API_KEY_FILE_NAME);
    }
}
