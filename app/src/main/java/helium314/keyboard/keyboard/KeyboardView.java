/*
 * Copyright (C) 2010 The Android Open Source Project
 * modified
 * SPDX-License-Identifier: Apache-2.0 AND GPL-3.0-only
 */

package helium314.keyboard.keyboard;

import static helium314.keyboard.keyboard.KeyboardTheme.STYLE_ROUNDED;

import android.content.Context;
import android.content.res.TypedArray;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Paint.Align;
import android.graphics.PorterDuff;
import android.graphics.RecordingCanvas;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.RenderNode;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.NinePatchDrawable;
import android.os.Build;
import android.text.TextUtils;
import android.util.AttributeSet;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.graphics.BlendModeColorFilterCompat;
import androidx.core.graphics.BlendModeCompat;
import androidx.core.graphics.ColorUtils;

import helium314.keyboard.keyboard.emoji.EmojiPageKeyboardView;
import helium314.keyboard.keyboard.internal.KeyDrawParams;
import helium314.keyboard.keyboard.internal.KeyVisualAttributes;
import helium314.keyboard.keyboard.internal.keyboard_parser.floris.KeyCode;
import helium314.keyboard.latin.R;
import helium314.keyboard.latin.common.ColorType;
import helium314.keyboard.latin.common.Colors;
import helium314.keyboard.latin.common.Constants;
import helium314.keyboard.latin.common.StringUtilsKt;
import helium314.keyboard.latin.settings.Settings;
import helium314.keyboard.latin.suggestions.MoreSuggestions;
import helium314.keyboard.latin.suggestions.MoreSuggestionsView;
import helium314.keyboard.latin.utils.TypefaceUtils;

import java.util.HashSet;

/** A view that renders a virtual {@link Keyboard}. */
// todo: this ThemeStyle-dependent stuff really should not be in here!
public class KeyboardView extends View {
    // XML attributes
    private final KeyVisualAttributes mKeyVisualAttributes;
    // Default keyLabelFlags from {@link KeyboardTheme}.
    // Currently only "alignHintLabelToBottom" is supported.
    private final int mDefaultKeyLabelFlags;
    private final float mKeyHintLetterPadding;
    private final String mKeyPopupHintLetter;
    private final float mKeyPopupHintLetterPadding;
    private final float mKeyShiftedLetterHintPadding;
    private final float mKeyTextShadowRadius;
    private final float mVerticalCorrection;
    private final Drawable mKeyBackground;
    private final Drawable mFunctionalKeyBackground;
    private final Drawable mActionKeyBackground;
    private final Drawable mSpacebarBackground;
    private final float mSpacebarIconWidthRatio;
    private final Rect mKeyBackgroundPadding = new Rect();
    private static final float KET_TEXT_SHADOW_RADIUS_DISABLED = -1.0f;
    private final Colors mColors;
    private float mKeyScaleForText;
    protected float mFontSizeMultiplier;
    protected float mHintFontSizeMultiplier;

    // The maximum key label width in the proportion to the key width.
    private static final float MAX_LABEL_RATIO = 0.90f;

    // Main keyboard
    // TODO: Consider having a dummy keyboard object to make this @NonNull
    @Nullable
    private Keyboard mKeyboard;
    @NonNull
    private final KeyDrawParams mKeyDrawParams = new KeyDrawParams();

    // Drawing
    /** True if all keys should be drawn */
    private boolean mInvalidateAllKeys;
    /** The keys that should be drawn */
    private final HashSet<Key> mInvalidatedKeys = new HashSet<>();
    /** The working rectangle for clipping */
    private final Rect mClipRect = new Rect();
    /** The keyboard bitmap buffer for faster updates */
    private Bitmap mOffscreenBuffer;
    private RenderNode mHardwareKeysNode;
    private int mNodeWidth = -1;
    private int mNodeHeight = -1;
    /** Flag for whether the key hints should be displayed */
    private boolean mShowsHints;
    /** Scale for downscaling icons and fixed size backgrounds if keyboard height is set below 80% */
    private float mIconScaleFactor;
    /** The canvas for the above mutable keyboard bitmap */
    @NonNull
    private final Canvas mOffscreenCanvas = new Canvas();
    @NonNull
    private final Paint mPaint = new Paint();
    private final Paint.FontMetrics mFontMetrics = new Paint.FontMetrics();
    @NonNull
    private final Paint mKeyCapPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    @NonNull
    private final RectF mKeyCapRect = new RectF();
    private boolean mIsDarkBackground = true;
    private int mCachedShiftIconColor = Color.TRANSPARENT;
    private int mFunctionalAccentColor = 0xFF7EE3EA;
    @Nullable
    private ColorFilter mShiftActiveIconColorFilter;
    @Nullable
    private ColorFilter mFunctionalAccentFilter;
    @Nullable
    private ColorFilter mWhiteIconFilter;

    public KeyboardView(final Context context, final AttributeSet attrs) {
        this(context, attrs, R.attr.keyboardViewStyle);
    }

    public KeyboardView(final Context context, final AttributeSet attrs, final int defStyle) {
        super(context, attrs, defStyle);
        mColors = Settings.getValues().mColors;

        final TypedArray keyboardViewAttr = context.obtainStyledAttributes(attrs,
                R.styleable.KeyboardView, defStyle, R.style.KeyboardView);
        if (this instanceof MoreSuggestionsView)
            mKeyBackground = mColors.selectAndColorDrawable(keyboardViewAttr, ColorType.MORE_SUGGESTIONS_WORD_BACKGROUND);
        else if (this instanceof PopupKeysKeyboardView)
            mKeyBackground = mColors.selectAndColorDrawable(keyboardViewAttr, ColorType.POPUP_KEYS_BACKGROUND);
        else
            mKeyBackground = mColors.selectAndColorDrawable(keyboardViewAttr, ColorType.KEY_BACKGROUND);
        mKeyBackground.getPadding(mKeyBackgroundPadding);
        mFunctionalKeyBackground = mColors.selectAndColorDrawable(keyboardViewAttr, ColorType.FUNCTIONAL_KEY_BACKGROUND);
        mSpacebarBackground = mColors.selectAndColorDrawable(keyboardViewAttr, ColorType.SPACE_BAR_BACKGROUND);
        if (this instanceof PopupKeysKeyboardView)
            mActionKeyBackground = mColors.selectAndColorDrawable(keyboardViewAttr, ColorType.ACTION_KEY_POPUP_KEYS_BACKGROUND);
        else
            mActionKeyBackground = mColors.selectAndColorDrawable(keyboardViewAttr, ColorType.ACTION_KEY_BACKGROUND);

        mSpacebarIconWidthRatio = keyboardViewAttr.getFloat(
                R.styleable.KeyboardView_spacebarIconWidthRatio, 1.0f);
        mKeyHintLetterPadding = keyboardViewAttr.getDimension(
                R.styleable.KeyboardView_keyHintLetterPadding, 0.0f);
        mKeyPopupHintLetter = Settings.getValues().mShowsPopupHints
                ? keyboardViewAttr.getString(R.styleable.KeyboardView_keyPopupHintLetter)
                : "";
        mKeyPopupHintLetterPadding = keyboardViewAttr.getDimension(
                R.styleable.KeyboardView_keyPopupHintLetterPadding, 0.0f);
        mKeyShiftedLetterHintPadding = keyboardViewAttr.getDimension(
                R.styleable.KeyboardView_keyShiftedLetterHintPadding, 0.0f);
        mKeyTextShadowRadius = keyboardViewAttr.getFloat(
                R.styleable.KeyboardView_keyTextShadowRadius, KET_TEXT_SHADOW_RADIUS_DISABLED);
        mVerticalCorrection = keyboardViewAttr.getDimension(
                R.styleable.KeyboardView_verticalCorrection, 0.0f);
        keyboardViewAttr.recycle();

        final TypedArray keyAttr = context.obtainStyledAttributes(attrs,
                R.styleable.Keyboard_Key, defStyle, R.style.KeyboardView);
        mDefaultKeyLabelFlags = keyAttr.getInt(R.styleable.Keyboard_Key_keyLabelFlags, 0);
        mKeyVisualAttributes = KeyVisualAttributes.newInstance(keyAttr);
        keyAttr.recycle();

        mPaint.setAntiAlias(true);
        setFitsSystemWindows(true);
    }

    @Nullable
    public KeyVisualAttributes getKeyVisualAttribute() {
        return mKeyVisualAttributes;
    }

    private static void blendAlpha(@NonNull final Paint paint, final int alpha) {
        final int color = paint.getColor();
        paint.setARGB((paint.getAlpha() * alpha) / Constants.Color.ALPHA_OPAQUE,
                Color.red(color), Color.green(color), Color.blue(color));
    }

    public void setHardwareAcceleratedDrawingEnabled(final boolean enabled) {
        if (!enabled) return;
        setLayerType(LAYER_TYPE_HARDWARE, null);
    }

    /**
     * Attaches a keyboard to this view. The keyboard can be switched at any time and the
     * view will re-layout itself to accommodate the keyboard.
     * @see Keyboard
     * @see #getKeyboard()
     * @param keyboard the keyboard to display in this view
     */
    public void setKeyboard(@NonNull final Keyboard keyboard) {
        if (keyboard instanceof MoreSuggestions) {
            mColors.setBackground(this, ColorType.MORE_SUGGESTIONS_BACKGROUND);
        } else if (keyboard instanceof PopupKeysKeyboard) {
            mColors.setBackground(this, ColorType.POPUP_KEYS_BACKGROUND);
        } else {
            // actual background color/drawable is applied to main_keyboard_frame
            setBackgroundColor(Color.TRANSPARENT);
        }

        mKeyboard = keyboard;
        mIsDarkBackground = ColorUtils.calculateLuminance(mColors.get(ColorType.MAIN_BACKGROUND)) < 0.5;
        mFunctionalAccentColor = mIsDarkBackground
                ? mColors.get(ColorType.GESTURE_TRAIL)
                : mColors.get(ColorType.ACTION_KEY_BACKGROUND);
        final int invertedGlyphColor = mIsDarkBackground ? Color.rgb(8, 8, 8) : Color.rgb(0xF2, 0xF2, 0xF0);
        if (mShiftActiveIconColorFilter == null || invertedGlyphColor != mCachedShiftIconColor) {
            mCachedShiftIconColor = invertedGlyphColor;
            mShiftActiveIconColorFilter = BlendModeColorFilterCompat.createBlendModeColorFilterCompat(
                    invertedGlyphColor, BlendModeCompat.SRC_IN);
        }
        mFunctionalAccentFilter = BlendModeColorFilterCompat.createBlendModeColorFilterCompat(
                mFunctionalAccentColor, BlendModeCompat.SRC_IN);
        if (mWhiteIconFilter == null) {
            mWhiteIconFilter = BlendModeColorFilterCompat.createBlendModeColorFilterCompat(
                    Color.rgb(0xF2, 0xF2, 0xF0), BlendModeCompat.SRC_IN);
        }
        mKeyScaleForText = (float) Math.sqrt(1 / Settings.getValues().mKeyboardHeightScale);
        int scale = Math.min(2 * keyboard.mMostCommonKeyWidth, keyboard.mMostCommonKeyHeight - keyboard.mVerticalGap);
        int scaledKeySize = (int) (scale * mKeyScaleForText);
        mKeyDrawParams.updateParams(scaledKeySize, mKeyVisualAttributes);
        mKeyDrawParams.updateParams(scaledKeySize, keyboard.mKeyVisualAttributes);
        invalidateAllKeys();
        requestLayout();
        mFontSizeMultiplier = mKeyboard.mId.getElement().isEmojiLayout()
                // In the case of EmojiKeyFit, the size of emojis is taken care of by the size of the keys
                ? (Settings.getValues().mEmojiKeyFit ? 1 : Settings.getValues().mFontSizeMultiplierEmoji)
                : Settings.getValues().mFontSizeMultiplier;
        mHintFontSizeMultiplier = Settings.getValues().mHintFontSizeMultiplier;
    }

    /**
     * Returns the current keyboard being displayed by this view.
     * @return the currently attached keyboard
     * @see #setKeyboard(Keyboard)
     */
    @Nullable
    public Keyboard getKeyboard() {
        return mKeyboard;
    }

    protected float getVerticalCorrection() {
        return mVerticalCorrection;
    }

    @NonNull
    protected KeyDrawParams getKeyDrawParams() {
        return mKeyDrawParams;
    }

    protected void updateKeyDrawParams(final int keyHeight) {
        mKeyDrawParams.updateParams(keyHeight, mKeyVisualAttributes);
    }

    @Override
    protected void onMeasure(final int widthMeasureSpec, final int heightMeasureSpec) {
        final Keyboard keyboard = getKeyboard();
        if (keyboard == null) {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec);
            return;
        }
        // The main keyboard expands to the entire this {@link KeyboardView}.
        final int width = keyboard.mOccupiedWidth + getPaddingLeft() + getPaddingRight();
        final int height = keyboard.mOccupiedHeight + getPaddingTop() + getPaddingBottom();
        setMeasuredDimension(width, height);
    }

    @Override
    protected void onDraw(@NonNull final Canvas canvas) {
        super.onDraw(canvas);
        if (canvas.isHardwareAccelerated()) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                if (mHardwareKeysNode == null) {
                    mHardwareKeysNode = new RenderNode("TaskKeys");
                }
                final int w = getWidth();
                final int h = getHeight();
                final boolean needsRecord = !mHardwareKeysNode.hasDisplayList()
                        || mNodeWidth != w || mNodeHeight != h
                        || mInvalidateAllKeys || !mInvalidatedKeys.isEmpty();
                if (needsRecord && w > 0 && h > 0) {
                    mNodeWidth = w;
                    mNodeHeight = h;
                    mHardwareKeysNode.setPosition(0, 0, w, h);
                    final RecordingCanvas rc = mHardwareKeysNode.beginRecording(w, h);
                    try {
                        onDrawKeyboard(rc);
                    } finally {
                        mHardwareKeysNode.endRecording();
                    }
                }
                if (mHardwareKeysNode.hasDisplayList()) {
                    canvas.drawRenderNode(mHardwareKeysNode);
                } else {
                    onDrawKeyboard(canvas);
                }
            } else {
                onDrawKeyboard(canvas);
            }
            com.tal.task.TaskHitboxBridge.drawOverlay(canvas, getKeyboard(), getContext());
            return;
        }

        final boolean bufferNeedsUpdates = mInvalidateAllKeys || !mInvalidatedKeys.isEmpty();
        if (bufferNeedsUpdates || mOffscreenBuffer == null) {
            if (maybeAllocateOffscreenBuffer()) {
                mInvalidateAllKeys = true;
                // TODO: Stop using the offscreen canvas even when in software rendering
                mOffscreenCanvas.setBitmap(mOffscreenBuffer);
            }
            onDrawKeyboard(mOffscreenCanvas);
        }
        canvas.drawBitmap(mOffscreenBuffer, 0.0f, 0.0f, null);
        com.tal.task.TaskHitboxBridge.drawOverlay(canvas, getKeyboard(), getContext());
    }

    private boolean maybeAllocateOffscreenBuffer() {
        final int width = getWidth();
        final int height = getHeight();
        if (width == 0 || height == 0) {
            return false;
        }
        if (mOffscreenBuffer != null && mOffscreenBuffer.getWidth() == width
                && mOffscreenBuffer.getHeight() == height) {
            return false;
        }
        freeOffscreenBuffer();
        mOffscreenBuffer = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        return true;
    }

    private void freeOffscreenBuffer() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && mHardwareKeysNode != null) {
            mHardwareKeysNode.discardDisplayList();
            mNodeWidth = -1;
            mNodeHeight = -1;
        }
        mOffscreenCanvas.setBitmap(null);
        mOffscreenCanvas.setMatrix(null);
        if (mOffscreenBuffer != null) {
            mOffscreenBuffer.recycle();
            mOffscreenBuffer = null;
        }
    }

    private void onDrawKeyboard(@NonNull final Canvas canvas) {
        final Keyboard keyboard = getKeyboard();
        if (keyboard == null) {
            return;
        }

        mShowsHints = Settings.getValues().mShowsHints;
        final float scale = Settings.getValues().mKeyboardHeightScale;
        mIconScaleFactor = scale < 0.8f ? scale + 0.2f : 1f;
        final Paint paint = mPaint;
        final Drawable background = getBackground();
        // Calculate clip region and set.
        final boolean drawAllKeys = mInvalidateAllKeys || mInvalidatedKeys.isEmpty();
        final boolean isHardwareAccelerated = canvas.isHardwareAccelerated();
        // TODO: Confirm if it's really required to draw all keys when hardware acceleration is on.
        if (drawAllKeys || isHardwareAccelerated) {
            if (!isHardwareAccelerated && background != null) {
                // Need to draw keyboard background on {@link #mOffscreenBuffer}.
                canvas.drawColor(Color.BLACK, PorterDuff.Mode.CLEAR);
                background.draw(canvas);
            }
            // Draw all keys.
            for (final Key key : keyboard.getSortedKeys()) {
                onDrawKey(key, canvas, paint);
            }
        } else {
            for (final Key key : mInvalidatedKeys) {
                if (!keyboard.hasKey(key)) {
                    continue;
                }
                if (background != null) {
                    // Need to redraw key's background on {@link #mOffscreenBuffer}.
                    final int x = key.getX() + getPaddingLeft();
                    final int y = key.getY() + getPaddingTop();
                    mClipRect.set(x, y, x + key.getWidth(), y + key.getHeight());
                    canvas.save();
                    canvas.clipRect(mClipRect);
                    canvas.drawColor(Color.BLACK, PorterDuff.Mode.CLEAR);
                    background.draw(canvas);
                    canvas.restore();
                }
                onDrawKey(key, canvas, paint);
            }
        }

        mInvalidatedKeys.clear();
        mInvalidateAllKeys = false;
    }

    private void onDrawKey(@NonNull final Key key, @NonNull final Canvas canvas,
            @NonNull final Paint paint) {
        final int keyDrawX = key.getDrawX() + getPaddingLeft();
        final int keyDrawY = key.getY() + getPaddingTop();
        canvas.translate(keyDrawX, keyDrawY);

        final KeyVisualAttributes attr = key.getVisualAttributes();
        // don't use the raw key height, linear font scaling with height is too extreme
        final KeyDrawParams params = mKeyDrawParams.mayCloneAndUpdateParams((int) (key.getHeight() * mKeyScaleForText), attr);
        params.mAnimAlpha = Constants.Color.ALPHA_OPAQUE;

        if (!key.isSpacer()) {
            final Drawable background = key.selectBackgroundDrawable(
                    mKeyBackground, mFunctionalKeyBackground, mSpacebarBackground, mActionKeyBackground);
            onDrawKeyBackground(key, canvas, background);
        }
        onDrawKeyTopVisuals(key, canvas, paint, params);

        canvas.translate(-keyDrawX, -keyDrawY);
    }

    private boolean isTaskRoundedMainKeyView() {
        return mColors.getHasKeyBorders()
                && STYLE_ROUNDED.equals(mColors.getThemeStyle())
                && !(this instanceof PopupKeysKeyboardView)
                && !(this instanceof MoreSuggestionsView)
                && !(this instanceof EmojiPageKeyboardView);
    }

    private static boolean isShiftActiveOnKeyboard(@NonNull final Key key, @Nullable final Keyboard keyboard) {
        return key.isShift() && keyboard != null && keyboard.mId.getElement().isAlphabetShifted();
    }

    private static boolean isAlphabetCapsLocked(@Nullable final Keyboard keyboard) {
        return keyboard != null && keyboard.mId.getElement() == KeyboardElement.ALPHABET_SHIFT_LOCKED;
    }

    private static boolean isFunctionalTintedKey(@NonNull final Key key) {
        final int code = key.getCode();
        if (code == KeyCode.EMOJI || code == KeyCode.LANGUAGE_SWITCH) {
            return false;
        }
        return key.hasFunctionalBackground() || code == ',' || code == '.';
    }

    // Draw key background.
    protected void onDrawKeyBackground(@NonNull final Key key, @NonNull final Canvas canvas,
            @NonNull final Drawable background) {
        final int keyWidth = key.getDrawWidth();
        final int keyHeight = key.getHeight();
        if (isTaskRoundedMainKeyView()) {
            if (key.getBackgroundType() == Key.BACKGROUND_TYPE_EMPTY) {
                return;
            }
            final float density = getResources().getDisplayMetrics().density;
            final float dp1 = Math.max(1f, density);
            final boolean isActionKey = key.hasActionKeyBackground();
            final float radius = isActionKey
                    ? Math.min(keyWidth, keyHeight) * 0.5f
                    : Math.min(12f * density, Math.min(keyWidth, keyHeight) * 0.28f);
            final Keyboard kb = getKeyboard();
            final boolean shiftActive = isShiftActiveOnKeyboard(key, kb);
            final boolean capsLocked = shiftActive && isAlphabetCapsLocked(kb);
            final int capColor;
            if (capsLocked) {
                capColor = mIsDarkBackground
                        ? mColors.get(ColorType.GESTURE_TRAIL)
                        : Color.rgb(8, 8, 8);
            } else if (shiftActive) {
                capColor = ColorUtils.blendARGB(
                        mColors.get(ColorType.FUNCTIONAL_KEY_BACKGROUND),
                        mFunctionalAccentColor,
                        0.22f);
            } else if (isActionKey) {
                capColor = mIsDarkBackground
                        ? mColors.get(ColorType.FUNCTIONAL_KEY_BACKGROUND)
                        : mColors.get(ColorType.ACTION_KEY_BACKGROUND);
            } else if (key.getBackgroundType() == Key.BACKGROUND_TYPE_SPACEBAR) {
                capColor = mColors.get(ColorType.SPACE_BAR_BACKGROUND);
            } else if (isFunctionalTintedKey(key)) {
                capColor = mColors.get(ColorType.FUNCTIONAL_KEY_BACKGROUND);
            } else {
                capColor = mColors.get(ColorType.KEY_BACKGROUND);
            }
            mKeyCapPaint.setStyle(Paint.Style.FILL);
            if (mIsDarkBackground) {
                final int finalColor = (key.isPressed() && !capsLocked)
                        ? ColorUtils.blendARGB(capColor, Color.WHITE, 0.12f)
                        : capColor;
                mKeyCapPaint.setColor(finalColor);
                mKeyCapRect.set(0f, 0f, keyWidth, keyHeight);
                canvas.drawRoundRect(mKeyCapRect, radius, radius, mKeyCapPaint);
            } else if (!key.isPressed()) {
                mKeyCapPaint.setColor(0x24000000);
                mKeyCapRect.set(0f, dp1, keyWidth, keyHeight);
                canvas.drawRoundRect(mKeyCapRect, radius, radius, mKeyCapPaint);
                mKeyCapPaint.setColor(capColor);
                mKeyCapRect.set(0f, 0f, keyWidth, Math.max(dp1, keyHeight - dp1));
                canvas.drawRoundRect(mKeyCapRect, radius, radius, mKeyCapPaint);
            } else {
                final int pressedColor = capsLocked
                        ? capColor
                        : ColorUtils.blendARGB(capColor, Color.BLACK, 0.08f);
                mKeyCapPaint.setColor(pressedColor);
                mKeyCapRect.set(0f, dp1, keyWidth, keyHeight);
                canvas.drawRoundRect(mKeyCapRect, radius, radius, mKeyCapPaint);
            }
            return;
        }
        final int bgWidth, bgHeight, bgX, bgY;
        if (key.needsToKeepBackgroundAspectRatio(mDefaultKeyLabelFlags)
                // HACK: To disable expanding normal/functional key background.
                && !key.hasCustomActionLabel()) {
            bgWidth = (int) (background.getIntrinsicWidth() * mIconScaleFactor);
            bgHeight = (int) (background.getIntrinsicHeight() * mIconScaleFactor);
            bgX = (keyWidth - bgWidth) / 2;
            bgY = (keyHeight - bgHeight) / 2;
        } else {
            final Rect padding = mKeyBackgroundPadding;
            bgWidth = keyWidth + padding.left + padding.right;
            bgHeight = keyHeight + padding.top + padding.bottom;
            bgY = -padding.top;
            bgX = -padding.left;
        }
        background.setBounds(0, 0, bgWidth, bgHeight);
        canvas.translate(bgX, bgY);
        background.draw(canvas);
        canvas.translate(-bgX, -bgY);
    }

    // Draw key top visuals.
    protected void onDrawKeyTopVisuals(@NonNull final Key key, @NonNull final Canvas canvas,
            @NonNull final Paint paint, @NonNull final KeyDrawParams params) {
        final int keyWidth = key.getDrawWidth();
        final int keyHeight = key.getHeight();
        final boolean taskCap = isTaskRoundedMainKeyView();
        final float dp1 = (taskCap && !mIsDarkBackground)
                ? Math.max(1f, getResources().getDisplayMetrics().density)
                : 0f;
        final float pressOffsetY = (taskCap && !mIsDarkBackground && key.isPressed()) ? dp1 : 0f;
        final float centerX = keyWidth * 0.5f;
        final float centerY = (keyHeight - dp1) * 0.5f + pressOffsetY;

        // Draw key label.
        final Keyboard keyboard = getKeyboard();
        final Drawable icon = (keyboard == null) ? null
                : key.getIcon(keyboard.mIconsSet, params.mAnimAlpha);
        float labelX = centerX;
        float labelBaseline = centerY;
        final String label = key.getLabel();
        if (label != null) {
            paint.setTypeface(KeyboardTypeface.resolve(label, key.selectTypeface(params)));
            paint.setTextSize(key.selectTextSize(params) * mFontSizeMultiplier);
            final boolean isSingleLower = label.length() == 1 && Character.isLowerCase(label.charAt(0));
            final float labelCharHeight = isSingleLower
                    ? TypefaceUtils.getLowercaseReferenceCharHeight(paint)
                    : TypefaceUtils.getReferenceCharHeight(paint);
            final float labelCharWidth = TypefaceUtils.getReferenceCharWidth(paint);

            // Vertical label text alignment.
            labelBaseline = centerY + labelCharHeight / 2.0f;

            // Horizontal label text alignment
            if (key.isAlignLabelOffCenter() && mShowsHints) {
                // The label is placed off center of the key. Currently used only on "phone number" layout
                // to have letter hints shown nicely. We don't want to align it off center if hints are off.
                // use a non-negative number to avoid label starting left of the letter for high keyboard scale on holo phone layout
                labelX = Math.max(0f, centerX + params.mLabelOffCenterRatio * labelCharWidth);
                paint.setTextAlign(Align.LEFT);
            } else {
                labelX = centerX;
                paint.setTextAlign(Align.CENTER);
            }
            if (key.needsAutoXScale()) {
                final int width;
                if (key.needsToKeepBackgroundAspectRatio(mDefaultKeyLabelFlags)) {
                    // make sure the text stays inside bounds of background drawable
                    Drawable bg = key.selectBackgroundDrawable(mKeyBackground, mFunctionalKeyBackground, mSpacebarBackground, mActionKeyBackground);
                    width = Math.min(bg.getBounds().bottom, bg.getBounds().right);
                } else width = keyWidth;
                final float ratio = Math.min(1.0f, (width * MAX_LABEL_RATIO) / TypefaceUtils.getStringWidth(label, paint));
                if (key.needsAutoScale()) {
                    final float autoSize = paint.getTextSize() * ratio;
                    paint.setTextSize(autoSize);
                } else {
                    paint.setTextScaleX(ratio);
                }
            }

            if (key.isEnabled()) {
                if (StringUtilsKt.isEmoji(label))
                    paint.setColor(key.selectTextColor(params) | 0xFF000000); // ignore alpha for emojis (though actually color isn't applied anyway and we could just set white)
                else if (taskCap && isShiftActiveOnKeyboard(key, keyboard))
                    paint.setColor(isAlphabetCapsLocked(keyboard) ? mCachedShiftIconColor : Color.rgb(0xF2, 0xF2, 0xF0));
                else if (taskCap && isFunctionalTintedKey(key))
                    paint.setColor(mFunctionalAccentColor);
                else if (key.hasActionKeyBackground())
                    paint.setColor((taskCap && mIsDarkBackground) ? mFunctionalAccentColor : mColors.get(ColorType.ACTION_KEY_ICON));
                else if (this instanceof EmojiPageKeyboardView)
                    paint.setColor(mColors.get(ColorType.EMOJI_KEY_TEXT));
                else if (this instanceof PopupKeysKeyboardView)
                    paint.setColor(mColors.get(ColorType.POPUP_KEY_TEXT));
                else
                    paint.setColor(key.selectTextColor(params));
                // Set a drop shadow for the text if the shadow radius is positive value.
                if (mKeyTextShadowRadius > 0.0f) {
                    paint.setShadowLayer(mKeyTextShadowRadius, 0.0f, 0.0f, params.mTextShadowColor);
                } else {
                    paint.clearShadowLayer();
                }
            } else {
                // Make label invisible
                paint.setColor(Color.TRANSPARENT);
                paint.clearShadowLayer();
            }
            blendAlpha(paint, params.mAnimAlpha);
            canvas.drawText(label, 0, label.length(), labelX, labelBaseline, paint);
            // Turn off drop shadow and reset x-scale.
            paint.clearShadowLayer();
            paint.setTextScaleX(1.0f);
        }

        // Draw hint label.
        String hintLabel = key.getHintLabel();
        Drawable hintIcon = (keyboard == null || !mShowsHints || hintLabel != null) ? null
                        : key.getHintIcon(keyboard.mIconsSet, params.mAnimAlpha);
        if (hintLabel != null && mShowsHints) {
            paint.setTextSize(key.selectHintTextSize(params) * mHintFontSizeMultiplier);
            paint.setColor(key.selectHintTextColor(params));
            paint.setTypeface(KeyboardTypeface.resolve(hintLabel, Typeface.DEFAULT_BOLD));
            blendAlpha(paint, params.mAnimAlpha);
            final float labelCharHeight = TypefaceUtils.getReferenceCharHeight(paint);
            final float labelCharWidth = TypefaceUtils.getReferenceCharWidth(paint);
            final boolean isFunctionalKeyAndRoundedStyle = mColors.getThemeStyle().equals(STYLE_ROUNDED) && key.hasFunctionalBackground();
            final float hintX, hintBaseline;
            if (key.hasHintLabel()) {
                // The hint label is placed just right of the key label. Used mainly on
                // "phone number" layout.
                hintX = labelX + params.mHintLabelOffCenterRatio * labelCharWidth;
                if (key.isAlignHintLabelToBottom(mDefaultKeyLabelFlags)) {
                    hintBaseline = labelBaseline;
                } else {
                    hintBaseline = centerY + labelCharHeight / 2.0f;
                }
                paint.setTextAlign(Align.LEFT);
                // shrink hint label before it's off the key
                // looks bad, but still better than the alternative
                final float ratio = Math.min(1.0f, (keyWidth - hintX) * 0.95f / TypefaceUtils.getStringWidth(hintLabel, paint));
                final float autoSize = paint.getTextSize() * ratio;
                paint.setTextSize(autoSize);
            } else if (key.hasShiftedLetterHint()) {
                // The hint label is placed at top-right corner of the key. Used mainly on tablet.
                hintX = keyWidth - mKeyShiftedLetterHintPadding - labelCharWidth / 2.0f;
                paint.getFontMetrics(mFontMetrics);
                hintBaseline = -mFontMetrics.top;
                paint.setTextAlign(Align.CENTER);
            } else if (taskCap && !isFunctionalKeyAndRoundedStyle) {
                final float density = getResources().getDisplayMetrics().density;
                hintX = keyWidth - 4f * density;
                hintBaseline = 3f * density - paint.ascent();
                paint.setTextAlign(Align.RIGHT);
            } else { // key.hasHintLetter()
                // The hint letter is placed at top-right corner of the key. Used mainly on phone.
                final float hintDigitWidth = TypefaceUtils.getReferenceDigitWidth(paint);
                final float hintLabelWidth = TypefaceUtils.getStringWidth(hintLabel, paint);
                hintBaseline = -paint.ascent();
                hintX = isFunctionalKeyAndRoundedStyle
                        ? keyWidth - hintBaseline
                        : keyWidth - mKeyHintLetterPadding - Math.max(hintDigitWidth, hintLabelWidth) / 2.0f;
                paint.setTextAlign(Align.CENTER);
            }
            final float adjustmentY = (taskCap && !key.hasHintLabel() && !key.hasShiftedLetterHint() && !isFunctionalKeyAndRoundedStyle)
                    ? pressOffsetY
                    : (isFunctionalKeyAndRoundedStyle
                            ? hintBaseline * 0.5f
                            : params.mHintLabelVerticalAdjustment * labelCharHeight);
            canvas.drawText(hintLabel, 0, hintLabel.length(), hintX, hintBaseline + adjustmentY, paint);
        } else if (hintIcon != null) {
            int iconSize = (int) (key.selectHintTextSize(params) * mHintFontSizeMultiplier);
            boolean isFunctionalKeyAndRoundedStyle = mColors.getThemeStyle().equals(STYLE_ROUNDED) && (key.hasFunctionalBackground() || key.hasActionKeyBackground());
            float hintX, hintBaseline;
            if (key.hasHintLabel()) {
                // The hint icon is placed just right of the key label. Used mainly on "phone number" layout.
                hintX = labelX + params.mHintLabelOffCenterRatio * iconSize;
                hintBaseline = centerY + iconSize / 2.0f;
            } else if (key.hasShiftedLetterHintIcon()) {
                // The hint label is placed at top-right corner of the key. Used mainly on tablet.
                hintX = keyWidth - mKeyShiftedLetterHintPadding - iconSize / 2.0f;
                paint.getFontMetrics(mFontMetrics);
                hintBaseline = -mFontMetrics.top;
            } else { // key.hasHintLetter()
                // The hint letter is placed at top-right corner of the key. Used mainly on phone.
                hintBaseline = 0;
                hintX = isFunctionalKeyAndRoundedStyle
                        ? keyWidth - iconSize * 1.5f
                        : keyWidth - mKeyHintLetterPadding - iconSize;
            }
            float adjustmentY = isFunctionalKeyAndRoundedStyle
                                      ? iconSize * 0.5f
                                      : params.mHintLabelVerticalAdjustment * iconSize;
            hintIcon.setColorFilter(key.selectHintTextColor(params), PorterDuff.Mode.MULTIPLY);
            drawIcon(canvas, hintIcon, (int)hintX, (int)(hintBaseline + adjustmentY), iconSize, iconSize);
        } else if (key.getPopupKeys() != null && ! key.hasNoPanelAutoPopupKey()
                        && (key.hasActionKeyBackground() || key.getBackgroundType() == Key.BACKGROUND_TYPE_FUNCTIONAL)) {
            drawKeyPopupHint(key, canvas, paint, params);
        }

        // Draw key icon.
        if (label == null && icon != null) {
            int iconWidth = key.getCode() == Constants.CODE_SPACE && icon instanceof NinePatchDrawable
                ? (int) (keyWidth * mSpacebarIconWidthRatio * mIconScaleFactor)
                : (int) (Math.min(icon.getIntrinsicWidth(), keyWidth) * mIconScaleFactor);
            int iconHeight = (int) (icon.getIntrinsicHeight() * mIconScaleFactor);
            int iconY = key.isAlignIconToBottom()
                ? keyHeight - iconHeight
                : ((keyHeight - iconHeight) / 2 + ((taskCap && !mIsDarkBackground) ? (int) (pressOffsetY - dp1 * 0.5f) : 0)); // Align vertically center.
            int iconX = (keyWidth - iconWidth) / 2; // Align horizontally center.
            setKeyIconColor(key, icon, keyboard);
            drawIcon(canvas, icon, iconX, iconY, iconWidth, iconHeight);
        }
    }

    // Draw popup hint "..." at the center or bottom right corner of the key, depending on style.
    protected void drawKeyPopupHint(@NonNull final Key key, @NonNull final Canvas canvas,
            @NonNull final Paint paint, @NonNull final KeyDrawParams params) {
        if (TextUtils.isEmpty(mKeyPopupHintLetter)) {
            return;
        }
        final int keyWidth = key.getDrawWidth();
        final int keyHeight = key.getHeight();
        final float labelCharWidth = TypefaceUtils.getReferenceCharWidth(paint);
        final float hintX;
        final float hintBaseline = paint.ascent();
        paint.setTypeface(params.mTypeface);
        paint.setTextSize(params.mHintLetterSize);
        paint.setColor(params.mHintLabelColor);
        paint.setTextAlign(Align.CENTER);
        if (mColors.getThemeStyle().equals(STYLE_ROUNDED)) {
            if (key.getBackgroundType() == Key.BACKGROUND_TYPE_SPACEBAR)
                hintX = keyWidth + hintBaseline + labelCharWidth * 0.1f;
            else
                hintX = key.hasFunctionalBackground() || key.hasActionKeyBackground() ? keyWidth / 2.0f : keyWidth - mKeyHintLetterPadding - labelCharWidth / 2.0f;
        } else {
            hintX = keyWidth - mKeyHintLetterPadding - TypefaceUtils.getReferenceCharWidth(paint) / 2.0f;
        }
        final float hintY = keyHeight - mKeyPopupHintLetterPadding;
        canvas.drawText(mKeyPopupHintLetter, hintX, hintY, paint);
    }

    protected static void drawIcon(@NonNull final Canvas canvas,@NonNull final Drawable icon,
            final int x, final int y, final int width, final int height) {
        canvas.translate(x, y);
        icon.setBounds(0, 0, width, height);
        icon.draw(canvas);
        canvas.translate(-x, -y);
    }

    public Paint newLabelPaint(@Nullable final Key key) {
        final Paint paint = new Paint();
        paint.setAntiAlias(true);
        if (key == null) {
            paint.setTypeface(mKeyDrawParams.mTypeface);
            paint.setTextSize(mKeyDrawParams.mLabelSize);
        } else {
            paint.setColor(key.selectTextColor(mKeyDrawParams));
            paint.setTypeface(key.selectTypeface(mKeyDrawParams));
            paint.setTextSize(key.selectTextSize(mKeyDrawParams) * mFontSizeMultiplier);
        }
        return paint;
    }

    /**
     * Requests a redraw of the entire keyboard. Calling {@link #invalidate} is not sufficient
     * because the keyboard renders the keys to an off-screen buffer and an invalidate() only
     * draws the cached buffer.
     * @see #invalidateKey(Key)
     */
    public void invalidateAllKeys() {
        mInvalidatedKeys.clear();
        mInvalidateAllKeys = true;
        invalidate();
    }

    /**
     * Invalidates a key so that it will be redrawn on the next repaint. Use this method if only
     * one key is changing it's content. Any changes that affect the position or size of the key
     * may not be honored.
     * @param key key in the attached {@link Keyboard}.
     * @see #invalidateAllKeys
     */
    public void invalidateKey(@Nullable final Key key) {
        if (mInvalidateAllKeys || key == null) {
            return;
        }
        mInvalidatedKeys.add(key);
        final int x = key.getX() + getPaddingLeft();
        final int y = key.getY() + getPaddingTop();
        invalidate(x, y, x + key.getWidth(), y + key.getHeight());
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        freeOffscreenBuffer();
    }

    public void deallocateMemory() {
        freeOffscreenBuffer();
    }

    private void setKeyIconColor(Key key, Drawable icon, Keyboard keyboard) {
        if (isTaskRoundedMainKeyView()) {
            if (isShiftActiveOnKeyboard(key, keyboard)) {
                if (isAlphabetCapsLocked(keyboard) && mShiftActiveIconColorFilter != null) {
                    icon.setColorFilter(mShiftActiveIconColorFilter);
                    return;
                }
                if (mWhiteIconFilter != null) {
                    icon.setColorFilter(mWhiteIconFilter);
                    return;
                }
            }
            if (key.hasActionKeyBackground()) {
                if (mIsDarkBackground && mFunctionalAccentFilter != null) {
                    icon.setColorFilter(mFunctionalAccentFilter);
                } else {
                    mColors.setColor(icon, ColorType.ACTION_KEY_ICON);
                }
                return;
            }
            if (key.getBackgroundType() != Key.BACKGROUND_TYPE_NORMAL
                    && key.getCode() != Constants.CODE_SPACE
                    && key.getCode() != KeyCode.ZWNJ
                    && mFunctionalAccentFilter != null) {
                icon.setColorFilter(mFunctionalAccentFilter);
                return;
            }
        }
        if (key.hasActionKeyBackground()) {
            mColors.setColor(icon, ColorType.ACTION_KEY_ICON);
        } else if (key.isShift() && keyboard != null) {
            if (keyboard.mId.getElement().isAlphabetShifted())
                mColors.setColor(icon, ColorType.SHIFT_KEY_ICON);
            else
                mColors.setColor(icon, ColorType.KEY_ICON); // normal key if not shifted
        } else if (key.getBackgroundType() != Key.BACKGROUND_TYPE_NORMAL) {
            mColors.setColor(icon, ColorType.KEY_ICON);
        } else if (this instanceof PopupKeysKeyboardView) {
            mColors.setColor(icon, ColorType.POPUP_KEY_ICON);
        } else if (key.getCode() == Constants.CODE_SPACE || key.getCode() == KeyCode.ZWNJ) {
            // set color of default number pad space bar icon for Holo style, or for zero-width non-joiner (zwnj) on some layouts like nepal
            mColors.setColor(icon, ColorType.KEY_ICON);
        } else {
            mColors.setColor(icon, ColorType.KEY_TEXT);
        }
    }

}
