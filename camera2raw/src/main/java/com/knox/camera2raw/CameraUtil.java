package com.knox.camera2raw;

import android.app.Activity;
import android.content.Context;
import android.graphics.ImageFormat;
import android.graphics.Point;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.util.Size;
import android.util.SparseIntArray;
import android.view.Surface;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.Locale;

/**
 * @author Knox.Tsang
 * @time 2019/2/26  16:33
 * @desc ${TODD}
 */

public class CameraUtil {

    //----------------------------------------------------------------------------------------------
    /**
     * Comparator based on area of the given {@link Size} objects.
     */
    static class CompareSizesByArea implements Comparator<Size> {

        @Override
        public int compare(Size lhs, Size rhs) {
            // We cast here to ensure the multiplication won't overflow
            return Long.signum((long) lhs.getWidth() * lhs.getHeight() - (long) rhs.getWidth() * rhs.getHeight());
        }
    }

    //----------------------------------------------------------------------------------------------
    /**
     * A Wrapper for an {@link AutoCloseable} object that implements reference counting to allow for resource manager
     */
    public static class RefCountedAutoCloseable<T extends AutoCloseable> implements AutoCloseable {
        private T mObject;
        private long mRefCount = 0;

        /**
         * Wrap the given object.
         *
         * @param object an object to wrap.
         */
        public RefCountedAutoCloseable(T object) {
            if (object == null) {
                throw new NullPointerException();
            }
            mObject = object;
        }

        /**
         * Increment the reference count and return the wrapped object.
         *
         * @return the wrapped object, or null if the object has been released.
         */
        public synchronized T getAndRetain() {
            if (mRefCount < 0) {
                return null;
            }
            mRefCount++;
            return mObject;
        }

        /**
         * Return the wrapped object.
         *
         * @return the wrapped object, or null if the object has been released.
         */
        public synchronized T get() {
            return mObject;
        }


        /**
         * Decrement the reference count and release the wrapped object if there are no other users retaining this object.
         */
        @Override
        public synchronized void close() {
            if (mRefCount >= 0) {
                mRefCount--;
                if (mRefCount < 0) {
                    try {
                        mObject.close();
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    } finally {
                        mObject = null;
                    }
                }
            }
        }
    }

    //----------------------------------------------------------------------------------------------
    /**
     * Return true 如果给定数组包含给定整数
     */
    public static boolean contains(int[] modes, int mode) {
        if (modes == null) {
            return false;
        }
        for (int i : modes) {
            if (i == mode) {
                return true;
            }
        }
        return false;
    }

    //----------------------------------------------------------------------------------------------
    public static Size getJpegRatio(StreamConfigurationMap map) {
        // 使用Collections和Comparator<Size>, Arrays.asList将数组转成集合
        return Collections.max(Arrays.asList(map.getOutputSizes(ImageFormat.JPEG)), new CameraUtil.CompareSizesByArea());
    }

    //----------------------------------------------------------------------------------------------
    public static Point getDisplaySize(Activity activity) {
        Point displaySize = new Point();
        activity.getWindowManager().getDefaultDisplay().getSize(displaySize);
        return displaySize;
    }

    //----------------------------------------------------------------------------------------------
    public static int getDisplayRotation(Activity activity) {
        return activity.getWindowManager().getDefaultDisplay().getRotation();
    }

    //----------------------------------------------------------------------------------------------
    /**
     * Conversion from screen rotation to JPEG orientation.
     */
    private static final SparseIntArray ORIENTATIONS = new SparseIntArray();

    static {
        ORIENTATIONS.append(Surface.ROTATION_0, 0);
        ORIENTATIONS.append(Surface.ROTATION_90, 90);
        ORIENTATIONS.append(Surface.ROTATION_180, 180);
        ORIENTATIONS.append(Surface.ROTATION_270, 270);
    }

    /**
     * Rotation need to transform from the camera sensor orientation to the device's current orientation.
     *
     * @param c                 the {@link CameraCharacteristics} to query for the camera sensor orientation.
     * @param deviceOrientation the current device orientation relative to the native device orientation.
     * @return the total rotation from the sensor orientation to the current device orientation.
     */
    public static int sensorToDeviceRotation(CameraCharacteristics c, int deviceOrientation) {
        int sensorOrientation = c.get(CameraCharacteristics.SENSOR_ORIENTATION);

        // Get device orientation in degrees
        deviceOrientation = ORIENTATIONS.get(deviceOrientation);

        // Reverse device orientation for front-facing cameras.
        if (c.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_FRONT) {
            deviceOrientation = -deviceOrientation;
        }

        // Calculate desired JPEG orientation relative to camera orientation to make the image
        // upright relative to the device orientation.
        int totalRotation = (sensorOrientation - deviceOrientation + 360) % 360;
        return totalRotation;
    }

    //----------------------------------------------------------------------------------------------
    /**
     * Generate a string containing a formatted timestamp with the current date and time.
     *
     * @return
     */
    public static String generateTimestamp() {
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy_MM_dd_HH_mm_ss_SSS", Locale.CHINA);
        return sdf.format(new Date());
    }

    //----------------------------------------------------------------------------------------------
    public static String getMp4FilePath(Context context) {
        final File dir = context.getExternalFilesDir(null);
        return (dir == null ? "" : (dir.getAbsolutePath() + "/") + System.currentTimeMillis() + ".mp4");
    }
}
