/*
 * Copyright (C) 2014 The Android Open Source Project
 * modified
 * SPDX-License-Identifier: Apache-2.0 AND GPL-3.0-only
 */

package helium314.keyboard.keyboard.internal;

import android.content.Context;
import android.view.Choreographer;
import android.view.View;
import android.view.ViewGroup;

import helium314.keyboard.keyboard.Key;
import helium314.keyboard.latin.common.ColorType;
import helium314.keyboard.latin.common.Colors;
import helium314.keyboard.latin.common.CoordinateUtils;
import helium314.keyboard.latin.settings.Settings;
import helium314.keyboard.latin.utils.ViewLayoutUtils;

import java.util.ArrayDeque;
import java.util.HashMap;

/**
 * T.A.S.K. v3.2.0 Key Preview Choreographer:
 * - Deterministic iOS key-extension geometry (0 measure(WRAP_CONTENT) calls on ACTION_DOWN).
 * - Analytical critically-damped SpringAnimation (zeta = 1.0, omega = 46 rad/s) on Choreographer
 *   preserving live scale and velocity on mid-flight interruptions.
 * - Explicit IME accessibility exception honoring ANIMATOR_DURATION_SCALE == 0 (D16).
 */
public final class KeyPreviewChoreographer {
    private static final float SPRING_OMEGA = 46.0f; // zeta = 1.0 -> ~95% settled in ~100 ms
    private static final float SCALE_EPS = 0.002f;
    private static final float VEL_EPS = 0.02f;

    private final ArrayDeque<KeyPreviewView> mFreeKeyPreviewViews = new ArrayDeque<>();
    private final HashMap<Key, KeyPreviewView> mShowingKeyPreviewViews = new HashMap<>();
    private final HashMap<Key, KeyPreviewView> mDismissingKeyPreviewViews = new HashMap<>();
    private final HashMap<KeyPreviewView, SpringState> mSpringStates = new HashMap<>();
    private final KeyPreviewDrawParams mParams;

    public KeyPreviewChoreographer(final KeyPreviewDrawParams params) {
        mParams = params;
    }

    public static boolean isImeReduceMotionEnabled(final Context context) {
        if (context == null) return false;
        try {
            return android.provider.Settings.Global.getFloat(
                    context.getContentResolver(),
                    android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1.0f) == 0.0f;
        } catch (final Throwable ignored) {
            return false;
        }
    }

    public KeyPreviewView getKeyPreviewView(final Key key, final ViewGroup placerView) {
        KeyPreviewView keyPreviewView = mShowingKeyPreviewViews.remove(key);
        if (keyPreviewView != null) {
            return keyPreviewView;
        }
        keyPreviewView = mDismissingKeyPreviewViews.remove(key);
        if (keyPreviewView != null) {
            final SpringState st = mSpringStates.get(keyPreviewView);
            if (st != null) st.dismissOnSettle = false;
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

    private void resetPreviewTransform(final KeyPreviewView view) {
        final SpringState st = mSpringStates.get(view);
        if (st != null) {
            st.stop();
            st.currentScale = 1.0f;
            st.currentAlpha = 1.0f;
            st.velocity = 0.0f;
            st.dismissOnSettle = false;
        }
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
        final KeyPreviewView view = mShowingKeyPreviewViews.remove(key);
        if (view == null) {
            return;
        }
        if (isImeReduceMotionEnabled(view.getContext())) {
            recyclePreviewImmediately(view);
            return;
        }
        mDismissingKeyPreviewViews.put(key, view);
        final SpringState st = getOrCreateSpringState(key, view);
        st.boundKey = key;
        st.targetScale = 0.94f;
        st.targetAlpha = 0.0f;
        st.dismissOnSettle = true;
        st.start();
    }

    public void placeAndShowKeyPreview(final Key key, final KeyboardIconsSet iconsSet,
            final KeyDrawParams drawParams, final int fullKeyboardViewWidth, final int[] keyboardOrigin,
            final ViewGroup placerView) {
        final KeyPreviewView keyPreviewView = getKeyPreviewView(key, placerView);
        placeKeyPreview(key, keyPreviewView, iconsSet, drawParams, fullKeyboardViewWidth, keyboardOrigin);
        showKeyPreview(key, keyPreviewView);
    }

    private void placeKeyPreview(final Key key, final KeyPreviewView keyPreviewView,
            final KeyboardIconsSet iconsSet, final KeyDrawParams drawParams,
            final int fullKeyboardViewWidth, final int[] originCoords) {
        final Colors colors = Settings.getValues().mColors;
        boolean hasPopupKeys = (key.getPopupKeys() != null);
        final float density = keyPreviewView.getResources().getDisplayMetrics().density;
        final int previewWidth = Math.max(key.getDrawWidth() + Math.round(10f * density), Math.round(38f * density));
        final int previewHeight = key.getHeight() + Math.round(16f * density);
        final int keyDrawWidth = key.getDrawWidth();
        final int originX = CoordinateUtils.x(originCoords);
        final int keyPreviewPosition;
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
        keyPreviewView.setPreviewBackground(hasPopupKeys, keyPreviewPosition);
        colors.setBackground(keyPreviewView, ColorType.KEY_PREVIEW_BACKGROUND);
        keyPreviewView.setPreviewVisual(key, iconsSet, drawParams);

        mParams.setDeterministicGeometry(
                previewWidth, previewHeight,
                keyPreviewView.getPaddingLeft(), keyPreviewView.getPaddingRight(),
                keyPreviewView.getPaddingTop(), keyPreviewView.getPaddingBottom());

        final int previewY = key.getY() - previewHeight + key.getHeight() - mParams.mPreviewOffset
                + CoordinateUtils.y(originCoords);

        ViewLayoutUtils.placeViewAt(keyPreviewView, previewX, previewY, previewWidth, previewHeight);
        keyPreviewView.requestLayout();
        keyPreviewView.setPivotX(previewWidth * 0.5f);
        keyPreviewView.setPivotY(previewHeight);
    }

    void showKeyPreview(final Key key, final KeyPreviewView keyPreviewView) {
        mShowingKeyPreviewViews.put(key, keyPreviewView);
        if (isImeReduceMotionEnabled(keyPreviewView.getContext())) {
            final SpringState st = mSpringStates.get(keyPreviewView);
            if (st != null) st.stop();
            keyPreviewView.setAlpha(1.0f);
            keyPreviewView.setScaleX(1.0f);
            keyPreviewView.setScaleY(1.0f);
            keyPreviewView.setVisibility(View.VISIBLE);
            return;
        }

        final SpringState st = getOrCreateSpringState(key, keyPreviewView);
        st.boundKey = key;
        if (keyPreviewView.getVisibility() != View.VISIBLE || !st.running) {
            st.currentScale = 0.88f;
            st.currentAlpha = 1.0f;
            st.velocity = 2.6f;
        }
        // Reverse cleanly from current live scale & velocity if interrupted mid-dismiss
        st.targetScale = 1.0f;
        st.targetAlpha = 1.0f;
        st.dismissOnSettle = false;
        keyPreviewView.setAlpha(st.currentAlpha);
        keyPreviewView.setScaleX(st.currentScale);
        keyPreviewView.setScaleY(st.currentScale);
        keyPreviewView.setVisibility(View.VISIBLE);
        st.start();
    }

    private void recyclePreviewImmediately(final KeyPreviewView view) {
        resetPreviewTransform(view);
        view.setVisibility(View.INVISIBLE);
        mFreeKeyPreviewViews.add(view);
    }

    private SpringState getOrCreateSpringState(final Key key, final KeyPreviewView view) {
        SpringState st = mSpringStates.get(view);
        if (st == null) {
            st = new SpringState(view);
            mSpringStates.put(view, st);
        }
        st.boundKey = key;
        return st;
    }

    private final class SpringState implements Choreographer.FrameCallback {
        final KeyPreviewView view;
        Key boundKey;
        float currentScale = 1.0f;
        float velocity = 0.0f;
        float currentAlpha = 1.0f;
        float targetScale = 1.0f;
        float targetAlpha = 1.0f;
        boolean dismissOnSettle = false;
        boolean running = false;
        long lastFrameNs = 0L;

        SpringState(final KeyPreviewView view) {
            this.view = view;
        }

        void start() {
            lastFrameNs = 0L;
            if (!running) {
                running = true;
                Choreographer.getInstance().postFrameCallback(this);
            }
        }

        void stop() {
            if (running) {
                running = false;
                Choreographer.getInstance().removeFrameCallback(this);
            }
            lastFrameNs = 0L;
        }

        @Override
        public void doFrame(final long frameTimeNanos) {
            if (!running) return;
            final float dt;
            if (lastFrameNs == 0L) {
                dt = 0.008f;
            } else {
                final float rawDt = (frameTimeNanos - lastFrameNs) * 1e-9f;
                dt = Math.max(0.001f, Math.min(0.033f, rawDt));
            }
            lastFrameNs = frameTimeNanos;

            // Analytical critically-damped spring integration (zeta = 1.0)
            final float dx = currentScale - targetScale;
            final float expTerm = (float) Math.exp(-SPRING_OMEGA * dt);
            final float c2 = velocity + SPRING_OMEGA * dx;
            currentScale = targetScale + (dx + c2 * dt) * expTerm;
            velocity = (velocity - SPRING_OMEGA * c2 * dt) * expTerm;

            if (dismissOnSettle) {
                currentAlpha = Math.max(0.0f, currentAlpha - dt / 0.065f);
            } else {
                currentAlpha = Math.min(1.0f, currentAlpha + dt / 0.045f);
            }

            view.setScaleX(currentScale);
            view.setScaleY(currentScale);
            view.setAlpha(currentAlpha);

            final boolean scaleSettled = Math.abs(currentScale - targetScale) <= SCALE_EPS
                    && Math.abs(velocity) <= VEL_EPS;
            final boolean alphaSettled = dismissOnSettle
                    ? currentAlpha <= 0.01f
                    : currentAlpha >= 0.99f;

            if (scaleSettled && alphaSettled) {
                running = false;
                lastFrameNs = 0L;
                if (dismissOnSettle) {
                    if (boundKey != null && mDismissingKeyPreviewViews.get(boundKey) == view) {
                        mDismissingKeyPreviewViews.remove(boundKey);
                    }
                    view.setVisibility(View.INVISIBLE);
                    view.setAlpha(1.0f);
                    view.setScaleX(1.0f);
                    view.setScaleY(1.0f);
                    currentScale = 1.0f;
                    currentAlpha = 1.0f;
                    velocity = 0.0f;
                    dismissOnSettle = false;
                    mFreeKeyPreviewViews.add(view);
                } else {
                    currentScale = targetScale;
                    currentAlpha = targetAlpha;
                    velocity = 0.0f;
                    view.setScaleX(currentScale);
                    view.setScaleY(currentScale);
                    view.setAlpha(currentAlpha);
                }
            } else {
                Choreographer.getInstance().postFrameCallback(this);
            }
        }
    }
}
