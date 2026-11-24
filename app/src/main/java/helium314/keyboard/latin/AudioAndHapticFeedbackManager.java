/*
 * Copyright (C) 2012 The Android Open Source Project
 * modified
 * SPDX-License-Identifier: Apache-2.0 AND GPL-3.0-only
 */

package helium314.keyboard.latin;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.SoundPool;
import android.os.Build;
import android.os.SystemClock;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.view.HapticFeedbackConstants;
import android.view.View;

import helium314.keyboard.event.HapticEvent;
import helium314.keyboard.keyboard.internal.keyboard_parser.floris.KeyCode;
import helium314.keyboard.latin.common.Constants;
import helium314.keyboard.latin.settings.SettingsValues;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;

/**
 * T.A.S.K. v3.3.0 Precision LRA Haptic & Low-Latency SoundPool Audio Manager.
 * - Zero per-keypress allocation: pre-builds LRA VibrationEffect primitives and loads 3 matte
 *   short PCM transient clicks (< 6 ms) into SoundPool at initInternal().
 * - Discrete haptic detent throttling (>= 22 ms) for cursor glide and delete-swipe.
 */
public final class AudioAndHapticFeedbackManager {
    private static final long MOVE_DETENT_MIN_INTERVAL_MS = 22L;

    private AudioManager mAudioManager;
    private Vibrator mVibrator;
    private boolean mHasVibrator;

    private SoundPool mSoundPool;
    private int mSfxLetterId = 0;
    private int mSfxDeleteId = 0;
    private int mSfxFuncId = 0;

    // Pre-allocated Precision LRA VibrationEffects
    private VibrationEffect mLetterTickEffect;
    private VibrationEffect mFunctionalClickEffect;
    private VibrationEffect mDetentTickEffect;
    private long mLastMoveTickUptimeMs = 0L;

    private SettingsValues mSettingsValues;
    private boolean mSoundOn;
    private boolean mDoNotDisturb;

    private static final AudioAndHapticFeedbackManager sInstance =
            new AudioAndHapticFeedbackManager();

    public static AudioAndHapticFeedbackManager getInstance() {
        return sInstance;
    }

    private AudioAndHapticFeedbackManager() {
        // Intentional empty constructor for singleton.
    }

    public static void init(final Context context) {
        sInstance.initInternal(context);
    }

    private void initInternal(final Context context) {
        mAudioManager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        mVibrator = (Vibrator) context.getSystemService(Context.VIBRATOR_SERVICE);
        mHasVibrator = mVibrator != null && mVibrator.hasVibrator();
        buildPrecisionHapticEffects();
        initSoundPool(context);
    }

    private void initSoundPool(final Context context) {
        if (mSoundPool != null) return;
        try {
            final AudioAttributes attrs = new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build();
            mSoundPool = new SoundPool.Builder()
                    .setMaxStreams(6)
                    .setAudioAttributes(attrs)
                    .build();

            final File sfxDir = new File(context.getFilesDir(), "task_sfx");
            if (!sfxDir.exists()) {
                //noinspection ResultOfMethodCallIgnored
                sfxDir.mkdirs();
            }
            final File letterWav = ensureClickWav(new File(sfxDir, "task_click_letter.wav"), 4.5f, 1680f, 840f, 0.0011f);
            final File deleteWav = ensureClickWav(new File(sfxDir, "task_click_delete.wav"), 5.2f, 980f, 490f, 0.0013f);
            final File funcWav = ensureClickWav(new File(sfxDir, "task_click_func.wav"), 5.5f, 1240f, 620f, 0.0014f);

            if (letterWav != null) mSfxLetterId = mSoundPool.load(letterWav.getAbsolutePath(), 1);
            if (deleteWav != null) mSfxDeleteId = mSoundPool.load(deleteWav.getAbsolutePath(), 1);
            if (funcWav != null) mSfxFuncId = mSoundPool.load(funcWav.getAbsolutePath(), 1);
        } catch (final Throwable ignored) {
            mSoundPool = null;
            mSfxLetterId = 0;
            mSfxDeleteId = 0;
            mSfxFuncId = 0;
        }
    }

    private static File ensureClickWav(final File outFile, final float durationMs,
            final float f1Hz, final float f2Hz, final float decayTauSec) {
        if (outFile.isFile() && outFile.length() > 44L) {
            return outFile;
        }
        final int sampleRate = 44100;
        final int numSamples = Math.max(64, Math.round(sampleRate * (durationMs / 1000.0f)));
        final int dataBytes = numSamples * 2;
        final byte[] wav = new byte[44 + dataBytes];

        // RIFF/WAVE PCM 16-bit mono header
        wav[0] = 'R'; wav[1] = 'I'; wav[2] = 'F'; wav[3] = 'F';
        writeIntLE(wav, 4, 36 + dataBytes);
        wav[8] = 'W'; wav[9] = 'A'; wav[10] = 'V'; wav[11] = 'E';
        wav[12] = 'f'; wav[13] = 'm'; wav[14] = 't'; wav[15] = ' ';
        writeIntLE(wav, 16, 16); // PCM chunk size
        writeShortLE(wav, 20, 1); // PCM format
        writeShortLE(wav, 22, 1); // mono
        writeIntLE(wav, 24, sampleRate);
        writeIntLE(wav, 28, sampleRate * 2);
        writeShortLE(wav, 32, 2); // block align
        writeShortLE(wav, 34, 16); // bits per sample
        wav[36] = 'd'; wav[37] = 'a'; wav[38] = 't'; wav[39] = 'a';
        writeIntLE(wav, 40, dataBytes);

        for (int i = 0; i < numSamples; i++) {
            final double t = (double) i / sampleRate;
            final double env = Math.exp(-t / decayTauSec) * (1.0 - (double) i / numSamples);
            final double sig = Math.sin(2.0 * Math.PI * f1Hz * t) * 0.65
                    + Math.sin(2.0 * Math.PI * f2Hz * t) * 0.35;
            final int pcm = (int) Math.max(-32767, Math.min(32767, Math.round(sig * env * 22000.0)));
            writeShortLE(wav, 44 + i * 2, pcm);
        }

        try (FileOutputStream fos = new FileOutputStream(outFile)) {
            fos.write(wav);
            return outFile;
        } catch (final IOException ignored) {
            return null;
        }
    }

    private static void writeIntLE(final byte[] buf, final int offset, final int value) {
        buf[offset] = (byte) (value & 0xFF);
        buf[offset + 1] = (byte) ((value >>> 8) & 0xFF);
        buf[offset + 2] = (byte) ((value >>> 16) & 0xFF);
        buf[offset + 3] = (byte) ((value >>> 24) & 0xFF);
    }

    private static void writeShortLE(final byte[] buf, final int offset, final int value) {
        buf[offset] = (byte) (value & 0xFF);
        buf[offset + 1] = (byte) ((value >>> 8) & 0xFF);
    }

    private void buildPrecisionHapticEffects() {
        if (!mHasVibrator || mVibrator == null) {
            return;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            final boolean supportsLowTick = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                    && mVibrator.areAllPrimitivesSupported(VibrationEffect.Composition.PRIMITIVE_LOW_TICK);
            final boolean supportsTick = mVibrator.areAllPrimitivesSupported(VibrationEffect.Composition.PRIMITIVE_TICK);
            final boolean supportsClick = mVibrator.areAllPrimitivesSupported(VibrationEffect.Composition.PRIMITIVE_CLICK);

            if (supportsLowTick) {
                mLetterTickEffect = VibrationEffect.startComposition()
                        .addPrimitive(VibrationEffect.Composition.PRIMITIVE_LOW_TICK, 0.45f)
                        .compose();
                mDetentTickEffect = VibrationEffect.startComposition()
                        .addPrimitive(VibrationEffect.Composition.PRIMITIVE_LOW_TICK, 0.35f)
                        .compose();
            } else if (supportsTick) {
                mLetterTickEffect = VibrationEffect.startComposition()
                        .addPrimitive(VibrationEffect.Composition.PRIMITIVE_TICK, 0.45f)
                        .compose();
                mDetentTickEffect = VibrationEffect.startComposition()
                        .addPrimitive(VibrationEffect.Composition.PRIMITIVE_TICK, 0.35f)
                        .compose();
            }
            if (supportsClick) {
                mFunctionalClickEffect = VibrationEffect.startComposition()
                        .addPrimitive(VibrationEffect.Composition.PRIMITIVE_CLICK, 0.65f)
                        .compose();
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            if (mLetterTickEffect == null) {
                mLetterTickEffect = VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK);
            }
            if (mDetentTickEffect == null) {
                mDetentTickEffect = VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK);
            }
            if (mFunctionalClickEffect == null) {
                mFunctionalClickEffect = VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK);
            }
        }
    }

    public void performHapticAndAudioFeedback(
        final int code,
        final View viewToPerformHapticFeedbackOn,
        final HapticEvent hapticEvent
    ) {
        performHapticFeedback(code, viewToPerformHapticFeedbackOn, hapticEvent);
        performAudioFeedback(code, hapticEvent);
    }

    public boolean hasVibrator() {
        return mHasVibrator;
    }

    public void vibrate(final long milliseconds) {
        if (!mHasVibrator || mVibrator == null || milliseconds <= 0) {
            return;
        }
        mVibrator.vibrate(VibrationEffect.createOneShot(milliseconds, VibrationEffect.DEFAULT_AMPLITUDE));
    }

    private boolean reevaluateIfSoundIsOn() {
        if (mSettingsValues == null || !mSettingsValues.mSoundOn || mAudioManager == null || mDoNotDisturb) {
            return false;
        }
        return mAudioManager.getRingerMode() == AudioManager.RINGER_MODE_NORMAL;
    }

    public void performAudioFeedback(final int code, final HapticEvent hapticEvent) {
        if (mAudioManager == null || !mSoundOn || hapticEvent != HapticEvent.KEY_PRESS) {
            return;
        }
        final int sfxId = switch (code) {
            case KeyCode.DELETE -> mSfxDeleteId;
            case Constants.CODE_ENTER, Constants.CODE_SPACE -> mSfxFuncId;
            default -> mSfxLetterId;
        };
        if (mSoundPool != null && sfxId != 0) {
            final float configuredVol = mSettingsValues != null ? mSettingsValues.mKeypressSoundVolume : 0.28f;
            final float vol = configuredVol > 0f ? Math.min(1.0f, configuredVol) : 0.28f;
            mSoundPool.play(sfxId, vol, vol, 1, 0, 1.0f);
            return;
        }
        final int sound = switch (code) {
            case KeyCode.DELETE -> AudioManager.FX_KEYPRESS_DELETE;
            case Constants.CODE_ENTER -> AudioManager.FX_KEYPRESS_RETURN;
            case Constants.CODE_SPACE -> AudioManager.FX_KEYPRESS_SPACEBAR;
            default -> AudioManager.FX_KEYPRESS_STANDARD;
        };
        mAudioManager.playSoundEffect(sound, mSettingsValues.mKeypressSoundVolume);
    }

    public void performHapticFeedback(final View viewToPerformHapticFeedbackOn, final HapticEvent hapticEvent) {
        performHapticFeedback(KeyCode.NOT_SPECIFIED, viewToPerformHapticFeedbackOn, hapticEvent);
    }

    public void performHapticFeedback(
            final int code,
            final View viewToPerformHapticFeedbackOn,
            final HapticEvent hapticEvent
    ) {
        if (mSettingsValues == null || !mSettingsValues.mVibrateOn
                || (mDoNotDisturb && !mSettingsValues.mVibrateInDndMode)) {
            return;
        }
        if (hapticEvent == HapticEvent.NO_HAPTICS) {
            return;
        }
        if (hapticEvent == HapticEvent.GESTURE_MOVE) {
            final long now = SystemClock.uptimeMillis();
            if (now - mLastMoveTickUptimeMs < MOVE_DETENT_MIN_INTERVAL_MS) {
                return;
            }
            mLastMoveTickUptimeMs = now;
        }
        if (hapticEvent.allowCustomDuration && mSettingsValues.mKeypressVibrationDuration >= 0) {
            vibrate(mSettingsValues.mKeypressVibrationDuration);
            return;
        }
        if (mHasVibrator && mVibrator != null) {
            final VibrationEffect effect;
            if (hapticEvent == HapticEvent.GESTURE_MOVE
                    || hapticEvent == HapticEvent.KEY_LONG_PRESS
                    || hapticEvent == HapticEvent.KEY_REPEAT) {
                effect = mDetentTickEffect;
            } else if (isFunctionalKey(code)) {
                effect = mFunctionalClickEffect;
            } else {
                effect = mLetterTickEffect;
            }
            if (effect != null) {
                mVibrator.vibrate(effect);
                return;
            }
        }
        if (viewToPerformHapticFeedbackOn != null) {
            viewToPerformHapticFeedbackOn.performHapticFeedback(
                    hapticEvent.feedbackConstant,
                    HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING);
        }
    }

    private static boolean isFunctionalKey(final int code) {
        return code == Constants.CODE_SPACE
                || code == Constants.CODE_ENTER
                || code == KeyCode.DELETE
                || code == KeyCode.SHIFT
                || code == KeyCode.SYMBOL_ALPHA
                || code == KeyCode.EMOJI;
    }

    public void onSettingsChanged(final SettingsValues settingsValues) {
        mSettingsValues = settingsValues;
        mSoundOn = reevaluateIfSoundIsOn();
    }

    public void onRingerModeChanged(boolean doNotDisturb) {
        mDoNotDisturb = doNotDisturb;
        mSoundOn = reevaluateIfSoundIsOn();
    }
}
