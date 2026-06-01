/*
 * SPDX-FileCopyrightText: 2026 NewPipe contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.schabi.newpipe.player.subtitle;

import android.graphics.Color;
import android.text.Spannable;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.text.style.RelativeSizeSpan;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.android.exoplayer2.text.Cue;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Decorates progressive Gemini subtitle cues as translations become available.
 */
public final class GeminiSubtitleRenderer {
    private static final Pattern CUE_MARKER = Pattern.compile(
            "^\u2063newpipe-gemini:([0-9a-f]+):(\\d+)\u2063");
    private static final Pattern TARGET_WORD = Pattern.compile(
            "[\\p{L}\\p{N}]+(?:['\u2019-][\\p{L}\\p{N}]+)*");
    private static final int[] RAINBOW_COLORS = {
            Color.rgb(255, 96, 96),
            Color.rgb(255, 180, 64),
            Color.rgb(255, 235, 80),
            Color.rgb(104, 225, 112),
            Color.rgb(80, 220, 225),
            Color.rgb(120, 165, 255),
            Color.rgb(205, 130, 255)
    };
    private static final Set<Runnable> LISTENERS = new CopyOnWriteArraySet<>();

    private GeminiSubtitleRenderer() { }

    public static void addListener(@NonNull final Runnable listener) {
        LISTENERS.add(listener);
    }

    public static void removeListener(@NonNull final Runnable listener) {
        LISTENERS.remove(listener);
    }

    static void notifyTranslationsChanged() {
        for (final Runnable listener : LISTENERS) {
            listener.run();
        }
    }

    @NonNull
    public static List<Cue> decorate(@NonNull final List<Cue> cues) {
        final List<Cue> decoratedCues = new ArrayList<>(cues.size());
        for (final Cue cue : cues) {
            decoratedCues.add(decorate(cue));
        }
        return decoratedCues;
    }

    @NonNull
    private static Cue decorate(@NonNull final Cue cue) {
        if (cue.text == null) {
            return cue;
        }

        final String text = cue.text.toString();
        final Matcher marker = CUE_MARKER.matcher(text);
        if (!marker.find()) {
            return cue;
        }

        final String sessionId = marker.group(1);
        final int cueIndex = Integer.parseInt(marker.group(2));
        final String source = text.substring(marker.end());
        final GeminiSubtitleDataSource.TranslationLine translation =
                GeminiSubtitleDataSource.getTranslation(sessionId, cueIndex);
        if (translation == null) {
            return cue.buildUpon().setText(source).build();
        }
        if (!GeminiSubtitleDataSource.shouldShowOriginal(sessionId)) {
            return cue.buildUpon().setText(translation.translation).build();
        }
        return cue.buildUpon().setText(buildBilingualText(source, translation)).build();
    }

    @NonNull
    private static CharSequence buildBilingualText(
            @NonNull final String source,
            @NonNull final GeminiSubtitleDataSource.TranslationLine translation) {
        final SpannableStringBuilder sourceText = new SpannableStringBuilder(source);
        sourceText.setSpan(new RelativeSizeSpan(0.75f), 0, sourceText.length(),
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        final SpannableStringBuilder targetText =
                new SpannableStringBuilder(translation.translation);
        final List<ColoredRange> targetColors = colorTargetWords(targetText);
        colorAlignedSourceWords(sourceText, translation, targetColors);
        return sourceText.append('\n').append(targetText);
    }

    @NonNull
    private static List<ColoredRange> colorTargetWords(
            @NonNull final SpannableStringBuilder targetText) {
        final List<ColoredRange> coloredRanges = new ArrayList<>();
        final Matcher targetWord = TARGET_WORD.matcher(targetText);
        int colorIndex = 0;
        while (targetWord.find()) {
            final int color = RAINBOW_COLORS[colorIndex % RAINBOW_COLORS.length];
            targetText.setSpan(new ForegroundColorSpan(color), targetWord.start(), targetWord.end(),
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            coloredRanges.add(new ColoredRange(targetWord.start(), targetWord.end(), color));
            colorIndex++;
        }
        return coloredRanges;
    }

    private static void colorAlignedSourceWords(
            @NonNull final SpannableStringBuilder sourceText,
            @NonNull final GeminiSubtitleDataSource.TranslationLine translation,
            @NonNull final List<ColoredRange> targetColors) {
        for (final GeminiSubtitleDataSource.Alignment alignment : translation.alignments) {
            final int targetStart = GeminiSubtitleDataSource.findOccurrence(
                    translation.translation, alignment.target, alignment.targetOccurrence, true);
            final Integer color = findColor(targetColors, targetStart);
            if (color == null) {
                continue;
            }

            final int sourceStart = GeminiSubtitleDataSource.findOccurrence(
                    sourceText.toString(), alignment.source, alignment.sourceOccurrence, false);
            if (sourceStart >= 0) {
                sourceText.setSpan(new ForegroundColorSpan(color), sourceStart,
                        sourceStart + alignment.source.length(),
                        Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
        }
    }

    @Nullable
    private static Integer findColor(@NonNull final List<ColoredRange> targetColors,
                                     final int targetStart) {
        for (final ColoredRange targetColor : targetColors) {
            if (targetStart >= targetColor.start && targetStart < targetColor.end) {
                return targetColor.color;
            }
        }
        return null;
    }

    private static final class ColoredRange {
        private final int start;
        private final int end;
        private final int color;

        private ColoredRange(final int start, final int end, final int color) {
            this.start = start;
            this.end = end;
            this.color = color;
        }
    }
}
