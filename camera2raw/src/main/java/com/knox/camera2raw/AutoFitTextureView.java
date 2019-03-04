package com.knox.camera2raw;

import android.content.Context;
import android.util.AttributeSet;
import android.util.Log;
import android.view.TextureView;

/**
 * @author Knox.Tsang
 * @time 2019/2/27  14:34
 * @desc ${TODD}
 */

/**
 * A {@link TextureView} that can be adjusted to a specified aspect ratio.
 */
public class AutoFitTextureView extends TextureView {

    private static final String TAG = "AutoFitTextureView";

    private int mRatioWidth = 0;
    private int mRatioHeight = 0;

    public AutoFitTextureView(Context context) {
        super(context, null);
    }

    public AutoFitTextureView(Context context, AttributeSet attrs) {
        super(context, attrs, 0);
    }

    public AutoFitTextureView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);
        int width = MeasureSpec.getSize(widthMeasureSpec);
        int height = MeasureSpec.getSize(heightMeasureSpec);
        Log.e(TAG, "onMeasure: org [width, height]=[" + width + ", " + height + "]");
        if (0 == mRatioWidth || 0 == mRatioHeight) {
            Log.e(TAG, "onMeasure: w=h=0 [width, height]=[" + width + ", " + height + "]");
            setMeasuredDimension(width, height);
        } else {
            if (width < height * mRatioWidth / mRatioHeight) {
                Log.e(TAG, "onMeasure: w<h*ratio [width, height]=[" + width + ", " + width * mRatioHeight / mRatioWidth + "]");
                setMeasuredDimension(width, width * mRatioHeight / mRatioWidth);
            } else {
                Log.e(TAG, "onMeasure: w>=h*ratio [width, height]=[" + height * mRatioWidth / mRatioHeight + ", " + height + "]");
                setMeasuredDimension(height * mRatioWidth / mRatioHeight, height);
            }
        }
    }

    /**
     * Sets the aspect ratio for this view. The size of the view will be measured based on the ratio
     * calculated from the parameters. Note that the actual sizes of parameters don't matter, that
     * is, calling setAspectRatio(2, 3) and setAspectRatio(4, 6) make the same result.
     *
     * @param width  Relative horizontal size
     * @param height Relative vertical size
     */
    public void setAspectRatio(int width, int height) {
        if (width < 0 || height < 0) {
            throw new IllegalArgumentException("Size cannot be negative.");
        }
        if (mRatioWidth == width && mRatioHeight == height) {
            return;
        }
        mRatioWidth = width;
        mRatioHeight = height;
        Log.e(TAG, "setAspectRatio: [width, height]=[" + mRatioWidth + ", " + mRatioHeight + "]");
        requestLayout();
    }
}
