/*
 * Copyright (C) 2014 The Android Open Source Project
 * modified
 * SPDX-License-Identifier: Apache-2.0 AND GPL-3.0-only
 */

package helium314.keyboard.keyboard.internal;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.Interpolator;
import android.view.animation.PathInterpolator;

import helium314.keyboard.keyboard.Key;
import helium314.keyboard.latin.common.ColorType;
import helium314.keyboard.latin.common.Colors;
import helium314.keyboard.latin.common.CoordinateUtils;
import helium314.keyboard.latin.settings.Settings;
import helium314.keyboard.latin.utils.ViewLayoutUtils;

import java.util.ArrayDeque;
import java.util.HashMap;

/**
 * T.A.S.K. Key Preview Choreographer with 120 Hz GPU micro-spring enter (0.92 -> 1.00 in 85 ms)
 * and interruptible fade-out (65 ms) without re-layout churn.
 */
public final class KeyPreviewChoreographer {
    private static final Interpolator ENTER_INTERPOLATOR = new PathInterpolator(0.2f, 0.8f, 0.2f, 1.0f);
    private static final Interpolator EXIT_INTERPOLATOR = new PathInterpolator(0.2f, 0.0f, 0.2f, 1.0f);
    private static final long ENTER_DURATION_MS = 85L;
    private static final long EXIT_DURATION_MS = 65L;

    private final ArrayDeque<KeyPreviewView> mFreeKeyPreviewViews = new ArrayDeque<>();
    private final HashMap<Key, KeyPreviewView> mShowingKeyPreviewViews = new HashMap<>();
    private final KeyPreviewDrawParams mParams;
    private int mDismissTokenSeq = 0;

    public KeyPreviewChoreographer(final KeyPreviewDrawParams params) {
        mParams = params;
    }

    public KeyPreviewView getKeyPreviewView(final Key key, final ViewGroup placerView) {
        KeyPreviewView keyPreviewView = mShowingKeyPreviewViews.remove(key);
        if (keyPreviewView != null) {
            resetPreviewTransform(keyPreviewView);
            return keyPreviewView;
        }
        keyPreviewView = mFreeKeyPreviewViews.poll();
        if (keyPreviewView != null) {
            resetPreviewTransform(keyPreviewView);
            return keyPreviewView;
        }
        final Context context = placerView.getContext();
        keyPreviewView = new KeyPreviewView(context, null /* attrs */);
        keyPreviewView.setBackgroundResource(mParams.mPreviewBackgroundResId);
        placerView.addView(keyPreviewView, ViewLayoutUtils.newLayoutParam(placerView, 0, 0));
        return keyPreviewView;
    }

    private static void resetPreviewTransform(final KeyPreviewView view) {
        view.animate().cancel();
        view.setTag(null);
        view.setAlpha(1.0f);
        view.setScaleX(1.0f);
        view.setScaleY(1.0f);
    }

    public boolean isShowingKeyPreview(final Key key) {
        return mShowingKeyPreviewViews.containsKey(key);
    }

    public void dismissKeyPreview(final Key key) {
        if (key == null) {
            return;
        }
        final KeyPreviewView keyPreviewView = mShowingKeyPreviewViews.get(key);
        if (keyPreviewView == null) {
            return;
        }
        mShowingKeyPreviewViews.remove(key);
        final Integer token = ++mDismissTokenSeq;
        keyPreviewView.setTag(token);
        keyPreviewView.animate().cancel();
        keyPreviewView.animate()
                .alpha(0.0f)
                .scaleX(0.95f)
                .scaleY(0.95f)
                .setDuration(EXIT_DURATION_MS)
                .setInterpolator(EXIT_INTERPOLATOR)
                .withEndAction(() -> {
                    if (token.equals(keyPreviewView.getTag())) {
                        keyPreviewView.setTag(null);
                        keyPreviewView.setVisibility(View.INVISIBLE);
                        keyPreviewView.setAlpha(1.0f);
                        keyPreviewView.setScaleX(1.0f);
                        keyPreviewView.setScaleY(1.0f);
                        mFreeKeyPreviewViews.add(keyPreviewView);
                    }
                })
                .start();
    }

    public void placeAndShowKeyPreview(final Key key, final KeyboardIconsSet iconsSet,
            final KeyDrawParams drawParams, final int fullKeyboardViewWidth, final int[] keyboardOrigin,
            final ViewGroup placerView) {
        final KeyPreviewView keyPreviewView = getKeyPreviewView(key, placerView);
        placeKeyPreview(key, keyPreviewView, iconsSet, drawParams, fullKeyboardViewWidth, keyboardOrigin);
        showKeyPreview(key, keyPreviewView);
    }

    private void placeKeyPreview(Key key, KeyPreviewView keyPreviewView, KeyboardIconsSet iconsSet,
            KeyDrawParams drawParams, int fullKeyboardViewWidth, int[] originCoords) {
        keyPreviewView.setPreviewVisual(key, iconsSet, drawParams);
        keyPreviewView.measure(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        mParams.setGeometry(keyPreviewView);
        int previewWidth = keyPreviewView.getMeasuredWidth();
        int previewHeight = keyPreviewView.getMeasuredHeight();
        int keyDrawWidth = key.getDrawWidth();
        int originX = CoordinateUtils.x(originCoords);
        int keyPreviewPosition;
        int previewX = key.getDrawX() - (previewWidth - keyDrawWidth) / 2 + originX;
        if (previewX < originX) {
            previewX = originX;
            keyPreviewPosition = KeyPreviewView.POSITION_LEFT;
        } else if (previewX > fullKeyboardViewWidth - previewWidth + originX) {
            previewX = fullKeyboardViewWidth - previewWidth + originX;
            keyPreviewPosition = KeyPreviewView.POSITION_RIGHT;
        } else {
            keyPreviewPosition = KeyPreviewView.POSITION_MIDDLE;
        }
        boolean hasPopupKeys = (key.getPopupKeys() != null);
        keyPreviewView.setPreviewBackground(hasPopupKeys, keyPreviewPosition);
        Colors colors = Settings.getValues().mColors;
        colors.setBackground(keyPreviewView, ColorType.KEY_PREVIEW_BACKGROUND);

        int previewY = key.getY() - previewHeight + key.getHeight() - mParams.mPreviewOffset
                + CoordinateUtils.y(originCoords);

        ViewLayoutUtils.placeViewAt(keyPreviewView, previewX, previewY, previewWidth, previewHeight);
        keyPreviewView.setPivotX(previewWidth / 2.0f);
        keyPreviewView.setPivotY(previewHeight);
    }

    void showKeyPreview(final Key key, final KeyPreviewView keyPreviewView) {
        keyPreviewView.animate().cancel();
        keyPreviewView.setTag(null);
        keyPreviewView.setAlpha(1.0f);
        keyPreviewView.setScaleX(0.92f);
        keyPreviewView.setScaleY(0.92f);
        keyPreviewView.setVisibility(View.VISIBLE);
        mShowingKeyPreviewViews.put(key, keyPreviewView);
        keyPreviewView.animate()
                .scaleX(1.0f)
                .scaleY(1.0f)
                .setDuration(ENTER_DURATION_MS)
                .setInterpolator(ENTER_INTERPOLATOR)
                .start();
    }
}
