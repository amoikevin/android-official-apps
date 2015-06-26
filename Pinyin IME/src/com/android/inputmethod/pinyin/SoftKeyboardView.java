/*
 * Copyright (C) 2009 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.inputmethod.pinyin;

import java.util.List;

import com.android.inputmethod.pinyin.SoftKeyboard.KeyRow;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Paint.FontMetricsInt;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.os.Vibrator;
import android.util.AttributeSet;
import android.view.View;

/**
 * Class used to show a soft keyboard.
 *
 * A soft keyboard view should not handle touch event itself, because we do bias
 * correction, need a global strategy to map an event into a proper view to
 * achieve better user experience.
 */
public class SoftKeyboardView extends View {
	/**
	 * The definition of the soft keyboard for the current this soft keyboard
	 * view.
	 */
	private SoftKeyboard mSoftKeyboard;

	/**
	 * The popup balloon hint for key press/release.
	 */
	private BalloonHint mBalloonPopup;

	/**
	 * The on-key balloon hint for key press/release. If it is null, on-key
	 * highlight will be drawn on th soft keyboard view directly.
	 */
	private BalloonHint mBalloonOnKey;

	/** Used to play key sounds. */
	private final SoundManager mSoundManager;

	/** The last key pressed. */
	private SoftKey mSoftKeyDown;

	/** Used to indicate whether the user is holding on a key. */
	private boolean mKeyPressed = false;

	/**
	 * The location offset of the view to the keyboard container.
	 */
	private final int mOffsetToSkbContainer[] = new int[2];

	/**
	 * The location of the desired hint view to the keyboard container.
	 */
	private final int mHintLocationToSkbContainer[] = new int[2];

	/**
	 * Text size for normal key.
	 */
	private int mNormalKeyTextSize;

	/**
	 * Text size for function key.
	 */
	private int mFunctionKeyTextSize;

	/**
	 * Long press timer used to response long-press.
	 */
	private SkbContainer.LongPressTimer mLongPressTimer;

	/**
	 * Repeated events for long press
	 */
	private final boolean mRepeatForLongPress = false;

	/**
	 * If this parameter is true, the balloon will never be dismissed even if
	 * user moves a lot from the pressed point.
	 */
	private boolean mMovingNeverHidePopupBalloon = false;

	/** Vibration for key press. */
	private Vibrator mVibrator;

	/** Vibration pattern for key press. */
	protected long[] mVibratePattern = new long[] { 1, 20 };

	/**
	 * The dirty rectangle used to mark the area to re-draw during key press and
	 * release. Currently, whenever we can invalidate(Rect), view will call
	 * onDraw() and we MUST draw the whole view. This dirty information is for
	 * future use.
	 */
	private final Rect mDirtyRect = new Rect();

	private final Paint mPaint;
	private final FontMetricsInt mFmi;
	private boolean mDimSkb;

	public SoftKeyboardView(final Context context, final AttributeSet attrs) {
		super(context, attrs);

		mSoundManager = SoundManager.getInstance(context);

		mPaint = new Paint();
		mPaint.setAntiAlias(true);
		mFmi = mPaint.getFontMetricsInt();
	}

	public boolean setSoftKeyboard(final SoftKeyboard softSkb) {
		if (null == softSkb)
			return false;
		mSoftKeyboard = softSkb;
		final Drawable bg = softSkb.getSkbBackground();
		if (null != bg)
			setBackgroundDrawable(bg);
		return true;
	}

	public SoftKeyboard getSoftKeyboard() {
		return mSoftKeyboard;
	}

	public void resizeKeyboard(final int skbWidth, final int skbHeight) {
		mSoftKeyboard.setSkbCoreSize(skbWidth, skbHeight);
	}

	public void setBalloonHint(final BalloonHint balloonOnKey, final BalloonHint balloonPopup,
			final boolean movingNeverHidePopup) {
		mBalloonOnKey = balloonOnKey;
		mBalloonPopup = balloonPopup;
		mMovingNeverHidePopupBalloon = movingNeverHidePopup;
	}

	public void setOffsetToSkbContainer(final int offsetToSkbContainer[]) {
		mOffsetToSkbContainer[0] = offsetToSkbContainer[0];
		mOffsetToSkbContainer[1] = offsetToSkbContainer[1];
	}

	@Override
	protected void onMeasure(final int widthMeasureSpec, final int heightMeasureSpec) {
		int measuredWidth = 0;
		int measuredHeight = 0;
		if (null != mSoftKeyboard) {
			measuredWidth = mSoftKeyboard.getSkbCoreWidth();
			measuredHeight = mSoftKeyboard.getSkbCoreHeight();
			measuredWidth += getPaddingLeft() + getPaddingRight();
			measuredHeight += getPaddingTop() + getPaddingBottom();
		}
		setMeasuredDimension(measuredWidth, measuredHeight);
	}

	private void showBalloon(final BalloonHint balloon, final int balloonLocationToSkb[], final boolean movePress) {
		long delay = BalloonHint.TIME_DELAY_SHOW;
		if (movePress)
			delay = 0;
		if (balloon.needForceDismiss())
			balloon.delayedDismiss(0);
		if (!balloon.isShowing())
			balloon.delayedShow(delay, balloonLocationToSkb);
		else
			balloon.delayedUpdate(delay, balloonLocationToSkb, balloon.getWidth(), balloon.getHeight());
		final long b = System.currentTimeMillis();
	}

	public void resetKeyPress(final long balloonDelay) {
		if (!mKeyPressed)
			return;
		mKeyPressed = false;
		if (null != mBalloonOnKey)
			mBalloonOnKey.delayedDismiss(balloonDelay);
		else if (null != mSoftKeyDown) {
			if (mDirtyRect.isEmpty())
				mDirtyRect.set(mSoftKeyDown.mLeft, mSoftKeyDown.mTop, mSoftKeyDown.mRight, mSoftKeyDown.mBottom);
			invalidate(mDirtyRect);
		} else
			invalidate();
		mBalloonPopup.delayedDismiss(balloonDelay);
	}

	// If movePress is true, means that this function is called because user
	// moves his finger to this button. If movePress is false, means that this
	// function is called when user just presses this key.
	public SoftKey onKeyPress(final int x, final int y, final SkbContainer.LongPressTimer longPressTimer,
			final boolean movePress) {
		mKeyPressed = false;
		boolean moveWithinPreviousKey = false;
		if (movePress) {
			final SoftKey newKey = mSoftKeyboard.mapToKey(x, y);
			if (newKey == mSoftKeyDown)
				moveWithinPreviousKey = true;
			mSoftKeyDown = newKey;
		} else
			mSoftKeyDown = mSoftKeyboard.mapToKey(x, y);
		if (moveWithinPreviousKey || null == mSoftKeyDown)
			return mSoftKeyDown;
		mKeyPressed = true;

		if (!movePress) {
			tryPlayKeyDown();
			tryVibrate();
		}

		mLongPressTimer = longPressTimer;

		if (!movePress) {
			if (mSoftKeyDown.getPopupResId() > 0 || mSoftKeyDown.repeatable())
				mLongPressTimer.startTimer();
		} else
			mLongPressTimer.removeTimer();

		int desired_width;
		int desired_height;
		float textSize;
		final Environment env = Environment.getInstance();

		if (null != mBalloonOnKey) {
			final Drawable keyHlBg = mSoftKeyDown.getKeyHlBg();
			mBalloonOnKey.setBalloonBackground(keyHlBg);

			// Prepare the on-key balloon
			final int keyXMargin = mSoftKeyboard.getKeyXMargin();
			final int keyYMargin = mSoftKeyboard.getKeyYMargin();
			desired_width = mSoftKeyDown.width() - 2 * keyXMargin;
			desired_height = mSoftKeyDown.height() - 2 * keyYMargin;
			textSize = env.getKeyTextSize(SoftKeyType.KEYTYPE_ID_NORMAL_KEY != mSoftKeyDown.mKeyType.mKeyTypeId);
			final Drawable icon = mSoftKeyDown.getKeyIcon();
			if (null != icon)
				mBalloonOnKey.setBalloonConfig(icon, desired_width, desired_height);
			else
				mBalloonOnKey.setBalloonConfig(mSoftKeyDown.getKeyLabel(), textSize, true, mSoftKeyDown.getColorHl(),
						desired_width, desired_height);

			mHintLocationToSkbContainer[0] = getPaddingLeft() + mSoftKeyDown.mLeft
					- (mBalloonOnKey.getWidth() - mSoftKeyDown.width()) / 2;
			mHintLocationToSkbContainer[0] += mOffsetToSkbContainer[0];
			mHintLocationToSkbContainer[1] = getPaddingTop() + mSoftKeyDown.mBottom - keyYMargin
					- mBalloonOnKey.getHeight();
			mHintLocationToSkbContainer[1] += mOffsetToSkbContainer[1];
			showBalloon(mBalloonOnKey, mHintLocationToSkbContainer, movePress);
		} else {
			mDirtyRect.union(mSoftKeyDown.mLeft, mSoftKeyDown.mTop, mSoftKeyDown.mRight, mSoftKeyDown.mBottom);
			invalidate(mDirtyRect);
		}

		// Prepare the popup balloon
		if (mSoftKeyDown.needBalloon()) {
			final Drawable balloonBg = mSoftKeyboard.getBalloonBackground();
			mBalloonPopup.setBalloonBackground(balloonBg);

			desired_width = mSoftKeyDown.width() + env.getKeyBalloonWidthPlus();
			desired_height = mSoftKeyDown.height() + env.getKeyBalloonHeightPlus();
			textSize = env.getBalloonTextSize(SoftKeyType.KEYTYPE_ID_NORMAL_KEY != mSoftKeyDown.mKeyType.mKeyTypeId);
			final Drawable iconPopup = mSoftKeyDown.getKeyIconPopup();
			if (null != iconPopup)
				mBalloonPopup.setBalloonConfig(iconPopup, desired_width, desired_height);
			else
				mBalloonPopup.setBalloonConfig(mSoftKeyDown.getKeyLabel(), textSize, mSoftKeyDown.needBalloon(),
						mSoftKeyDown.getColorBalloon(), desired_width, desired_height);

			// The position to show.
			mHintLocationToSkbContainer[0] = getPaddingLeft() + mSoftKeyDown.mLeft
					+ -(mBalloonPopup.getWidth() - mSoftKeyDown.width()) / 2;
			mHintLocationToSkbContainer[0] += mOffsetToSkbContainer[0];
			mHintLocationToSkbContainer[1] = getPaddingTop() + mSoftKeyDown.mTop - mBalloonPopup.getHeight();
			mHintLocationToSkbContainer[1] += mOffsetToSkbContainer[1];
			showBalloon(mBalloonPopup, mHintLocationToSkbContainer, movePress);
		} else
			mBalloonPopup.delayedDismiss(0);

		if (mRepeatForLongPress)
			longPressTimer.startTimer();
		return mSoftKeyDown;
	}

	public SoftKey onKeyRelease(final int x, final int y) {
		mKeyPressed = false;
		if (null == mSoftKeyDown)
			return null;

		mLongPressTimer.removeTimer();

		if (null != mBalloonOnKey)
			mBalloonOnKey.delayedDismiss(BalloonHint.TIME_DELAY_DISMISS);
		else {
			mDirtyRect.union(mSoftKeyDown.mLeft, mSoftKeyDown.mTop, mSoftKeyDown.mRight, mSoftKeyDown.mBottom);
			invalidate(mDirtyRect);
		}

		if (mSoftKeyDown.needBalloon())
			mBalloonPopup.delayedDismiss(BalloonHint.TIME_DELAY_DISMISS);

		if (mSoftKeyDown.moveWithinKey(x - getPaddingLeft(), y - getPaddingTop()))
			return mSoftKeyDown;
		return null;
	}

	public SoftKey onKeyMove(final int x, final int y) {
		if (null == mSoftKeyDown)
			return null;

		if (mSoftKeyDown.moveWithinKey(x - getPaddingLeft(), y - getPaddingTop()))
			return mSoftKeyDown;

		// The current key needs to be updated.
		mDirtyRect.union(mSoftKeyDown.mLeft, mSoftKeyDown.mTop, mSoftKeyDown.mRight, mSoftKeyDown.mBottom);

		if (mRepeatForLongPress) {
			if (mMovingNeverHidePopupBalloon)
				return onKeyPress(x, y, mLongPressTimer, true);

			if (null != mBalloonOnKey)
				mBalloonOnKey.delayedDismiss(0);
			else
				invalidate(mDirtyRect);

			if (mSoftKeyDown.needBalloon())
				mBalloonPopup.delayedDismiss(0);

			if (null != mLongPressTimer)
				mLongPressTimer.removeTimer();
			return onKeyPress(x, y, mLongPressTimer, true);
		} else
			// When user moves between keys, repeated response is disabled.
			return onKeyPress(x, y, mLongPressTimer, true);
	}

	private void tryVibrate() {
		if (!Settings.getVibrate())
			return;
		if (mVibrator == null)
			mVibrator = (Vibrator) getContext().getSystemService(Context.VIBRATOR_SERVICE);
		mVibrator.vibrate(mVibratePattern, -1);
	}

	private void tryPlayKeyDown() {
		if (Settings.getKeySound())
			mSoundManager.playKeyDown();
	}

	public void dimSoftKeyboard(final boolean dimSkb) {
		mDimSkb = dimSkb;
		invalidate();
	}

	@Override
	protected void onDraw(final Canvas canvas) {
		if (null == mSoftKeyboard)
			return;

		canvas.translate(getPaddingLeft(), getPaddingTop());

		final Environment env = Environment.getInstance();
		mNormalKeyTextSize = env.getKeyTextSize(false);
		mFunctionKeyTextSize = env.getKeyTextSize(true);
		// Draw the last soft keyboard
		final int rowNum = mSoftKeyboard.getRowNum();
		final int keyXMargin = mSoftKeyboard.getKeyXMargin();
		final int keyYMargin = mSoftKeyboard.getKeyYMargin();
		for (int row = 0; row < rowNum; row++) {
			final KeyRow keyRow = mSoftKeyboard.getKeyRowForDisplay(row);
			if (null == keyRow)
				continue;
			final List<SoftKey> softKeys = keyRow.mSoftKeys;
			final int keyNum = softKeys.size();
			for (int i = 0; i < keyNum; i++) {
				final SoftKey softKey = softKeys.get(i);
				if (SoftKeyType.KEYTYPE_ID_NORMAL_KEY == softKey.mKeyType.mKeyTypeId)
					mPaint.setTextSize(mNormalKeyTextSize);
				else
					mPaint.setTextSize(mFunctionKeyTextSize);
				drawSoftKey(canvas, softKey, keyXMargin, keyYMargin);
			}
		}

		if (mDimSkb) {
			mPaint.setColor(0xa0000000);
			canvas.drawRect(0, 0, getWidth(), getHeight(), mPaint);
		}

		mDirtyRect.setEmpty();
	}

	private void drawSoftKey(final Canvas canvas, final SoftKey softKey, final int keyXMargin, final int keyYMargin) {
		Drawable bg;
		int textColor;
		if (mKeyPressed && softKey == mSoftKeyDown) {
			bg = softKey.getKeyHlBg();
			textColor = softKey.getColorHl();
		} else {
			bg = softKey.getKeyBg();
			textColor = softKey.getColor();
		}

		if (null != bg) {
			bg.setBounds(softKey.mLeft + keyXMargin, softKey.mTop + keyYMargin, softKey.mRight - keyXMargin,
					softKey.mBottom - keyYMargin);
			bg.draw(canvas);
		}

		final String keyLabel = softKey.getKeyLabel();
		final Drawable keyIcon = softKey.getKeyIcon();
		if (null != keyIcon) {
			final Drawable icon = keyIcon;
			final int marginLeft = (softKey.width() - icon.getIntrinsicWidth()) / 2;
			final int marginRight = softKey.width() - icon.getIntrinsicWidth() - marginLeft;
			final int marginTop = (softKey.height() - icon.getIntrinsicHeight()) / 2;
			final int marginBottom = softKey.height() - icon.getIntrinsicHeight() - marginTop;
			icon.setBounds(softKey.mLeft + marginLeft, softKey.mTop + marginTop, softKey.mRight - marginRight,
					softKey.mBottom - marginBottom);
			icon.draw(canvas);
		} else if (null != keyLabel) {
			mPaint.setColor(textColor);
			final float x = softKey.mLeft + (softKey.width() - mPaint.measureText(keyLabel)) / 2.0f;
			final int fontHeight = mFmi.bottom - mFmi.top;
			final float marginY = (softKey.height() - fontHeight) / 2.0f;
			final float y = softKey.mTop + marginY - mFmi.top + mFmi.bottom / 1.5f;
			canvas.drawText(keyLabel, x, y + 1, mPaint);
		}
	}
}
