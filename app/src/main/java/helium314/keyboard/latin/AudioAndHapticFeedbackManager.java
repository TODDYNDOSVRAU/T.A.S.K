/*
 * Copyright (C) 2012 The Android Open Source Project
 * modified
 * SPDX-License-Identifier: Apache-2.0 AND GPL-3.0-only
 */

package helium314.keyboard.latin;

import android.content.Context;
import android.media.AudioManager;
import android.os.Build;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.view.HapticFeedbackConstants;
import android.view.View;

import helium314.keyboard.event.HapticEvent;
import helium314.keyboard.keyboard.internal.keyboard_parser.floris.KeyCode;
import helium314.keyboard.latin.common.Constants;
import helium314.keyboard.latin.settings.SettingsValues;

/**
 * T.A.S.K. Apple Taptic Engine-style Haptic & Audio Feedback Manager.
 * Zero per-keypress allocation: pre-builds LRA VibrationEffect primitives at initInternal().
 */
public final class AudioAndHapticFeedbackManager {
    private AudioManager mAudioManager;
    private Vibrator mVibrator;
    private boolean mHasVibrator;

    // Pre-allocated Taptic Engine VibrationEffects (B4.Q2)
    private VibrationEffect mLetterTickEffect;
    private VibrationEffect mFunctionalClickEffect;
    private VibrationEffect mDetentTickEffect;

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
        buildTapticEffects();
    }

    private void buildTapticEffects() {
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
