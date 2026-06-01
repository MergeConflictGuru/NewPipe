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
        colorAlignedSourceWords(sourceText, targetText, translation, targetColors);
        return sourceText.append('\n').append(targetText);
    }

    @NonNull
    private static List<ColoredRange> colorTargetWords(
            @NonNull final SpannableStringBuilder targetText) {
        final List<ColoredRange> coloredRanges = new ArrayList<>();
        final Matcher targetWord = TARGET_WORD.matcher(targetText);
        while (targetWord.find()) {
            coloredRanges.add(new ColoredRange(targetWord.start(), targetWord.end(), 0));
        }
        for (int i = 0; i < coloredRanges.size(); i++) {
            final ColoredRange range = coloredRanges.get(i);
            final int color = rainbowColorAt(i, coloredRanges.size());
            final ColoredRange coloredRange = new ColoredRange(range.start, range.end, color);
            targetText.setSpan(coloredRange.span, range.start, range.end,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            coloredRanges.set(i, coloredRange);
        }
        return coloredRanges;
    }

    static int rainbowColorAt(final int wordIndex, final int wordCount) {
        if (wordCount <= 1) {
            return RAINBOW_COLORS[0];
        }
        final float palettePosition = (float) wordIndex * (RAINBOW_COLORS.length - 1)
                / (wordCount - 1);
        final int lowerIndex = (int) Math.floor(palettePosition);
        final int upperIndex = Math.min(lowerIndex + 1, RAINBOW_COLORS.length - 1);
        final float fraction = palettePosition - lowerIndex;
        return Color.rgb(
                interpolate(Color.red(RAINBOW_COLORS[lowerIndex]),
                        Color.red(RAINBOW_COLORS[upperIndex]), fraction),
                interpolate(Color.green(RAINBOW_COLORS[lowerIndex]),
                        Color.green(RAINBOW_COLORS[upperIndex]), fraction),
                interpolate(Color.blue(RAINBOW_COLORS[lowerIndex]),
                        Color.blue(RAINBOW_COLORS[upperIndex]), fraction));
    }

    private static int interpolate(final int from, final int to, final float fraction) {
        return Math.round(from + (to - from) * fraction);
    }

    private static void colorAlignedSourceWords(
            @NonNull final SpannableStringBuilder sourceText,
            @NonNull final SpannableStringBuilder targetText,
            @NonNull final GeminiSubtitleDataSource.TranslationLine translation,
            @NonNull final List<ColoredRange> targetColors) {
        for (final GeminiSubtitleDataSource.Alignment alignment : translation.alignments) {
            final int targetStart = GeminiSubtitleDataSource.findOccurrence(
                    translation.translation, alignment.target, alignment.targetOccurrence, true);
            final Integer color = findFirstColor(targetColors, targetStart);
            if (color == null) {
                continue;
            }
            if (countWords(alignment.source) > 1 || countWords(alignment.target) > 1) {
                colorTargetRange(targetText, targetColors, targetStart,
                        targetStart + alignment.target.length(), color);
            }
        }

        for (final GeminiSubtitleDataSource.Alignment alignment : translation.alignments) {
            final int targetStart = GeminiSubtitleDataSource.findOccurrence(
                    translation.translation, alignment.target, alignment.targetOccurrence, true);
            final Integer color = findFirstColor(targetColors, targetStart);
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

    private static void colorTargetRange(@NonNull final SpannableStringBuilder targetText,
                                         @NonNull final List<ColoredRange> targetColors,
                                         final int targetStart,
                                         final int targetEnd,
                                         final int color) {
        for (final ColoredRange targetColor : targetColors) {
            if (targetStart < targetColor.end && targetEnd > targetColor.start) {
                targetText.removeSpan(targetColor.span);
                targetColor.color = color;
                targetColor.span = new ForegroundColorSpan(color);
                targetText.setSpan(targetColor.span, targetColor.start, targetColor.end,
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
        }
    }

    private static Integer findFirstColor(@NonNull final List<ColoredRange> targetColors,
                                          final int targetStart) {
        for (final ColoredRange targetColor : targetColors) {
            if (targetStart >= targetColor.start && targetStart < targetColor.end) {
                return targetColor.color;
            }
        }
        return null;
    }

    private static int countWords(@NonNull final String text) {
        int wordCount = 0;
        final Matcher sourceWord = TARGET_WORD.matcher(text);
        while (sourceWord.find()) {
            wordCount++;
        }
        return wordCount;
    }

    private static final class ColoredRange {
        private final int start;
        private final int end;
        private int color;
        private ForegroundColorSpan span;

        private ColoredRange(final int start, final int end, final int color) {
            this.start = start;
            this.end = end;
            this.color = color;
            this.span = new ForegroundColorSpan(color);
        }
    }
}
