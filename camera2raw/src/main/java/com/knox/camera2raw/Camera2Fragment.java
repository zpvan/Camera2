package com.knox.camera2raw;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.ImageFormat;
import android.graphics.Matrix;
import android.graphics.Point;
import android.graphics.RectF;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CameraMetadata;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.CaptureResult;
import android.hardware.camera2.TotalCaptureResult;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.Image;
import android.media.ImageReader;
import android.media.MediaRecorder;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.v4.app.ActivityCompat;
import android.support.v4.app.Fragment;
import android.support.v4.app.FragmentActivity;
import android.util.Log;
import android.util.Size;
import android.util.SparseIntArray;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Semaphore;

import static com.knox.camera2raw.CameraUtil.contains;
import static com.knox.camera2raw.CameraUtil.generateTimestamp;
import static com.knox.camera2raw.CameraUtil.getDisplayRotation;
import static com.knox.camera2raw.CameraUtil.getDisplaySize;
import static com.knox.camera2raw.CameraUtil.getJpegRatio;
import static com.knox.camera2raw.CameraUtil.getMp4FilePath;
import static com.knox.camera2raw.CameraUtil.sensorToDeviceRotation;

/**
 * @author Knox.Tsang
 * @time 2019/2/25  17:22
 * @desc ${TODD}
 */

/**
 * 预览和截图和录制
 *
 *  1. 重写Fragment生命周期的回调函数
 *  2. 准备一条background-thread来做耗时的任务
 *     2.1 抓图返回时是在background-thread, 同时在background-thread中保存图片文件
 *  3. 由于同时存在UI-thread和background-thread, 所以需要保证两条thread同时会用到变量线程同步
 *  4. 准备Camera设备
 *  5. 申请运行时权限, Fragment的回调流程onResume->requestPermissions->onPause->onRequestPermissionsResult->onResume
 *     https://blog.csdn.net/guayunfanlove/article/details/80106241
 *  6. 打开Camera
 *     6.1 Camera预览要跟TextureView配合使用, 但是TextureView需要由wms和sf配合创建, 两者可用的时机不能确定,
 *         Camera的可用回调是onOpened, 在background-thread, TextureView的可用回调是onSurfaceTextureAvailable, 在main-thread
 *         TextureView可主动调用isAvailable()来查询是否可用
 *     6.2 综上所述, 我们可以在3个地方去打开预览, 这3个地方都必须保证Camera和TextureView都可用, 否则不能call预览函数
 *         在onOpened的回调中, 通过设置mState来说明Camera可用
 *         TextureView则用isAvailable()来判断是否可用
 *         6.2.1 main-thread在TextureView的可用回调onSurfaceTextureAvailable中判断是否可以开启预览
 *         6.2.2 background-thread在Camera的可用回调onOpened中判断是否可以开启预览
 *         6.2.3 main-thread在OpenCamera之后可以马上判断是否可以开启预览
 *     6.3 其实只有前两点的位置也可以奏效, 即onSurfaceTextureAvailable中或onOpened中
 *  7. 截图控件-AutoFitTextureView-匹配最佳的预览尺寸, 使得预览窗口在横竖屏时都一样大小
 *     7.1 由于AutoFitTextureView的宽高需要知道抓图的宽高来调节, 所以必须要在(步骤4)后设置回调监听
 *     7.2 同时在回调onSurfaceTextureAvailable和onSurfaceTextureSizeChanged中调用confirgureTransform来调整预览View的宽高
 *  8. 横竖屏旋转
 *     8.1 竖屏的时候不需要做特别处理也正常
 *     8.2 横屏的时候, 图像会改变方向也变形
 *         8.2.1 先用x,y方向的不等比缩放, 将图像与preview时设置的width, height相等, 使得图像不变形
 *         8.2.2 再用x,y方向的等比缩放, 将图像的面积做到跟TextureView的面积相等
 *         8.2.3 最后旋转图像, 使得与TextureView吻合
 *  9. 预览操作
 *     9.1 与Camera创建会话createCaptureSession, 要说明会话中有哪些surface, 譬如预览和抓图
 *     9.2 会话连接成功后, 构建预览请求, 添加目标surface
 *     9.3 利用会话发送预览请求
 * 10. 抓图操作(短按)
 *     10.1 在预览请求的基础上, 添加自动对焦, 自动曝光, 自动白平衡请求
 *     10.2 替代原先仅仅预览的请求, 向会话发出预览加3A的请求
 *     10.3 等待auto-focus, auto-exposure, auto-white-balance结束
 *     10.4 创建新的请求, 用来抓图
 *     10.5 要添加ImageReader的Surface作为Target
 *     10.6 旋转保证抓图与预览同方向
 *     10.7 向会话发送抓图请求
 *     10.8 等待onImageAvailable回调
 *     10.9 有了image和file, 开始写文件
 * 11. 录制操作(长按)
 *     11.1 长按Picture开始
 *     11.2 关闭预览会话
 *     11.3 准备MediaRecorder用来编码和封装
 *     11.4 创建录制请求
 *     11.5 创建录制会话
 *     11.6 向录制会话发送录制请求
 *     11.7 启动MediaRecorder
 *     11.8 松手关闭录制重启预览
 *     11.9 释放MediaRecorder
 *     11.A 关闭录制会话
 *     11.B 重新开始预览会话和发送预览请求
 */
public class Camera2Fragment extends Fragment implements View.OnLongClickListener, View.OnClickListener, View.OnTouchListener {

    private static final String TAG = "Camera2Fragment";

    /**
     * Permissions required to take a picture.
     */
    private static final String[] CAMERA_PERMISSIONS = {
            Manifest.permission.CAMERA,
            Manifest.permission.READ_EXTERNAL_STORAGE,
            Manifest.permission.WRITE_EXTERNAL_STORAGE,
            Manifest.permission.RECORD_AUDIO,
    };
    /**
     * Request code for camera permissions.
     */
    private static final int REQUEST_CAMERA_PERMISSIONS = 226;

    /**
     * Camera state: Device is closed.
     */
    private static final int STATE_CLOSED = 0;

    /**
     * Camera state: Device is opened, but is not capturing.
     */
    private static final int STATE_OPENED = 1;

    /**
     * Camera state: Showing camera preview.
     */
    private static final int STATE_PREVIEW = 2;

    /**
     * Camera state: Waiting for 3A convergence before capturing a photo.
     */
    private static final int STATE_WAITING_FOR_3A_CONVERGENCE = 3;

    /**
     * Max preview width that is guaranteed by Camera2 API
     */
    private static final int MAX_PREVIREW_WIDTH = 1920;

    /**
     * Max preview height that is guaranteed by Camera2 API
     */
    private static final int MAX_PREVIEW_HEIGHT = 1080;

    /**
     * 预设Preview的Size是1280x960, 这是通过google demo获取的
     */
    private static final int PREFER_PREVIEW_WIDTH = 1280;
    private static final int PREFER_PREVIEW_HEIGHT = 960;

    /**
     * 预设Record的Size是800x600, 这是通过google demo获取的
     */
    private static final int PREFER_RECORD_WIDTH = 800;
    private static final int PREFER_RECORD_HEIGHT = 600;

    /**
     * Timeout for the pre-capture sequence.
     */
    private static final long PRECAPTURE_TIMEOUT_MS = 1000;

    private static final int SENSOR_ORIENTATION_DEFAULT_DEGREES = 90;
    private static final int SENSOR_ORIENTATION_INVERSE_DEGREES = 270;

    private static final SparseIntArray DEFAULT_ORIENTATIONS = new SparseIntArray();
    private static final SparseIntArray INVERSE_ORIENTATIONS = new SparseIntArray();

    static {
        DEFAULT_ORIENTATIONS.append(Surface.ROTATION_0, 90);
        DEFAULT_ORIENTATIONS.append(Surface.ROTATION_90, 0);
        DEFAULT_ORIENTATIONS.append(Surface.ROTATION_180, 270);
        DEFAULT_ORIENTATIONS.append(Surface.ROTATION_270, 180);
    }

    static {
        INVERSE_ORIENTATIONS.append(Surface.ROTATION_0, 270);
        INVERSE_ORIENTATIONS.append(Surface.ROTATION_90, 180);
        INVERSE_ORIENTATIONS.append(Surface.ROTATION_180, 90);
        INVERSE_ORIENTATIONS.append(Surface.ROTATION_270, 0);
    }

    /**
     * Widget
     */
    private AutoFitTextureView mTextureView;
    private Button mPictureBtn;

    /**
     * A {@link Handler} for running tasks in the background.
     */
    private Handler mBackgroundHandler;

    /**
     * An additional thread for running tasks that shouldn't block the UI.
     * This is used for all callbacks from the {@link android.hardware.camera2.CameraDevice} and {@link android.hardware.camera2.CameraCaptureSession}s
     */
    private HandlerThread mBackgroundThread;

    /**
     * A reference counted holder wrapping the {@link ImageReader} that handles JPEG image
     * captures. This is used to allow us to clean up the {@link ImageReader} when all background
     * tasks using its {@link android.media.Image}s have completed.
     */
    private CameraUtil.RefCountedAutoCloseable<ImageReader> mJpegImageReader;

    private final ImageReader.OnImageAvailableListener mOnJpegImageAvailableListener = new ImageReader.OnImageAvailableListener() {
        @Override
        public void onImageAvailable(ImageReader reader) {
            synchronized (mCameraStateLock) {
                // 等待onImageAvailable回调, 说明抓图成功, 避免应用进入退出时, 而reader又在写文件, reader意外关掉
                // 这里使用mJpegImageReader替代回调返回的reader
                if (mJpegImageReader == null || mJpegImageReader.getAndRetain() == null) {
                    Log.e(TAG, "onImageAvailable: Paused the activity before we could save the image, ImageReader already closed.");
                    return;
                }
                final Image image = mJpegImageReader.get().acquireNextImage();
                if (image == null) {
                    Log.e(TAG, "onImageAvailable: can't acquire image");
                    return;
                }
                // TODO 避免线程太多, 应该用线程池, 任务也应用放到队列里边
                new Thread(new Runnable() {
                    @Override
                    public void run() {
                        String currentDateTime = generateTimestamp();
                        File jpegFile = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM),
                                "JPEG_" + currentDateTime + ".jpg");
                        // 有了image和file, 开始写文件, 且减少reader的引用
                        if (dumpJpegToFile(jpegFile, image)) {
                            Log.e(TAG, "run: dumpJpeg success [path]=[" + jpegFile.getAbsolutePath() + "]");
                        }
                        mJpegImageReader.close();
                    }
                }).start();
            }
        }
    };

    /**
     * The {@link CameraCharacteristics} for the currently configured camera device.
     */
    private CameraCharacteristics mCharacteristics;

    /**
     * A {@link Semaphore} to prevent the app from exiting before closing the camera.
     */
    private final Semaphore mCameraOpenCloseLock = new Semaphore(1);

    /**
     * A lock protecting camera state.
     */
    private final Object mCameraStateLock = new Object();

    // 由于同时存在UI-thread和background-thread, 所以需要保证两条thread同时会用到变量线程同步
    //**********************************************************************************************
    // State protected by mCameraStateLock.
    //
    // The following state is used across both the UI and background threads. Methods with "Locked"
    // in the name expect mCameraStateLock to be held while calling.

    /**
     * ID of the current {@link android.hardware.camera2.CameraDevice}
     */
    private String mCameraId;

    /**
     * A reference to the open {@link CameraDevice}
     */
    private CameraDevice mCameraDevice;

    /**
     * {@link CaptureRequest.Builder} for the camera preview.
     */
    private CaptureRequest.Builder mPreviewCaptureRequest;

    /**
     * The state of the camera device.
     *
     *
     */
    private int mState = STATE_CLOSED;

    /**
     * A {@link CameraCaptureSession} for camera preview.
     */
    private CameraCaptureSession mCaptureSession;

    /**
     * Timer to use with pre-capture sequence to ensure a timely capture if 3A convergence is taking
     * too long.
     */
    private long mCaptureTimer;

    /**
     * Number of pending user requests to capture a photo.
     */
    private int mPendingUserCaptures = 0;

    private boolean mRecording = false;

    /**
     * MediaRecorder
     */
    private MediaRecorder mMediaRecorder;

    private CaptureRequest.Builder mRecodeBuilder;

    private String mNextVideoAbsolutePath;

    private Integer mSensorOrientation;

    private CameraCaptureSession mRecordSession;

    //**********************************************************************************************

    /**
     * 所有回调都是在background-thread中
     *
     * {@link CameraDevice.StateCallback} is called when the currently active {@link CameraDevice}
     * changes its state.
     */
    private final CameraDevice.StateCallback mStateCallback = new CameraDevice.StateCallback() {
        @Override
        public void onOpened(@NonNull CameraDevice camera) {
            // This method is called when the camera is opened. We start camera preview here if
            // the TextureView displaying this has been set up.
            synchronized (mCameraStateLock) {
                mCameraDevice = camera;
                mState = STATE_OPENED;
                // TODO 打开预览
                if (mTextureView.isAvailable()) {
                    createCameraPreviewSessionLocked();
                }
            }
        }

        @Override
        public void onDisconnected(@NonNull CameraDevice camera) {
            synchronized (mCameraStateLock) {
                mState = STATE_CLOSED;
                mCameraDevice.close();
                mCameraDevice = null;
            }
        }

        @Override
        public void onError(@NonNull CameraDevice camera, int error) {
            synchronized (mCameraStateLock) {
                mState = STATE_CLOSED;
                mCameraDevice.close();
                mCameraDevice = null;
            }
            FragmentActivity activity = getActivity();
            if (null != activity) {
                activity.finish();
            }
        }
    };

    //**********************************************************************************************

    /**
     * {@link TextureView.SurfaceTextureListener} handles several lifecycle events of a {@link TextureView}
     */
    private final TextureView.SurfaceTextureListener mSurfaceTextureListener =
            new TextureView.SurfaceTextureListener() {
                @Override
                public void onSurfaceTextureAvailable(SurfaceTexture surface, int width, int height) {
                    confirgureTransform(width, height);
                }

                @Override
                public void onSurfaceTextureSizeChanged(SurfaceTexture surface, int width, int height) {
                    confirgureTransform(width, height);
                }

                @Override
                public boolean onSurfaceTextureDestroyed(SurfaceTexture surface) {
                    return false;
                }

                @Override
                public void onSurfaceTextureUpdated(SurfaceTexture surface) {

                }
            };

    //**********************************************************************************************

    private CameraCaptureSession.CaptureCallback mPreCaptureCallback = new CameraCaptureSession.CaptureCallback() {

        private void process(CaptureResult result) {
            synchronized (mCameraStateLock) {
                switch (mState) {
                    case STATE_WAITING_FOR_3A_CONVERGENCE:
                        // 等待auto-focus, auto-exposure, auto-white-balance结束
                        boolean readyToCapture = false;

                        // It we have hit our maximum wait timeout, too bad! Begin capture anyway.
                        if (!hitTimeoutLocked()) {
                            readyToCapture = true;
                        } else {
                            // 如果AF的状态是LOCKED, 说明已经完成
                            Integer afState = result.get(CaptureResult.CONTROL_AF_STATE);
                            if (afState == null) {
                                break;
                            }
                            readyToCapture = (afState == CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED || afState == CaptureResult.CONTROL_AF_STATE_NOT_FOCUSED_LOCKED);

                            // 如果AE的状态是CONVERGED, 说明已经完成
                            Integer aeSate = result.get(CaptureResult.CONTROL_AE_STATE);
                            if (aeSate == null) {
                                break;
                            }
                            readyToCapture = readyToCapture && (aeSate == CaptureResult.CONTROL_AE_STATE_CONVERGED);

                            // 如果AWB的状态是CONVERGED, 说明已经完成
                            Integer awbState = result.get(CaptureResult.CONTROL_AWB_STATE);
                            if (awbState == null) {
                                break;
                            }
                            readyToCapture = readyToCapture && (awbState == CaptureResult.CONTROL_AWB_STATE_CONVERGED);
                        }

                        if (readyToCapture && mPendingUserCaptures > 0) {
                            while (mPendingUserCaptures > 0) {
                                captureStillPictureLocked();
                                mPendingUserCaptures--;
                            }
                            // After this, the camera will go back to the normal state of preview.
                            mState = STATE_PREVIEW;
                        }

                        break;
                }
            }
        }

        @Override
        public void onCaptureProgressed(@NonNull CameraCaptureSession session, @NonNull CaptureRequest request, @NonNull CaptureResult partialResult) {
            process(partialResult);
        }

        @Override
        public void onCaptureCompleted(@NonNull CameraCaptureSession session, @NonNull CaptureRequest request, @NonNull TotalCaptureResult result) {
            process(result);
        }
    };

    //**********************************************************************************************

    //----------------------------------------------------------------------------------------------
    public static Fragment newInstance() {
        return new Camera2Fragment();
    }

    // 重写Fragment生命周期的回调函数
    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        // inflate出Fragment的布局
        return inflater.inflate(R.layout.fragment_camera2_raw, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        // 找到rootView里边的控件
        mTextureView = view.findViewById(R.id.tex_tv);
        mPictureBtn = view.findViewById(R.id.picture_btn);
        mPictureBtn.setOnLongClickListener(this);
        mPictureBtn.setOnClickListener(this);
        mPictureBtn.setOnTouchListener(this);
    }

    @Override
    public void onResume() {
        super.onResume();
        startBackgroundThread();
        if (!prepareCamera()) {
            return;
        }
        if (!hasAllPermissionGranted()) {
            requestCameraPermissions();
            return;
        }
        openCamera();
        // 预览控件
        mTextureView.setSurfaceTextureListener(mSurfaceTextureListener);
        // TODO
    }

    @Override
    public void onPause() {
        closeCamera();
        stopBackgroundThread();
        super.onPause();
    }

    // 开启background thread
    private void startBackgroundThread() {
        mBackgroundThread = new HandlerThread("BGD");
        mBackgroundThread.start();
        synchronized (mCameraStateLock) {
            mBackgroundHandler = new Handler(mBackgroundThread.getLooper());
        }
    }

    /**
     * Stops the background thread and its {@link Handler}
     */
    private void stopBackgroundThread() {
        mBackgroundThread.quitSafely();
        try {
            mBackgroundThread.join();
            mBackgroundThread = null;
            synchronized (mCameraStateLock) {
                mBackgroundHandler = null;
            }
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }

    // 准备Camera设备
    private boolean prepareCamera() {
        FragmentActivity activity = getActivity();
        if (activity == null) {
            return false;
        }
        CameraManager manager = (CameraManager) getActivity().getSystemService(Context.CAMERA_SERVICE);
        if (manager == null) {
            // TODO ErrorDialog
            return false;
        }
        try {
            // 找到一个支持RAW抓图的Camera, 同时配置环境
            for (String cameraId : manager.getCameraIdList()) {
                CameraCharacteristics characteristics = manager.getCameraCharacteristics(cameraId);

                // 筛选支持RAW抓图的Camera
                if (!contains(characteristics.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES),
                        CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_RAW)) {
                    continue;
                }

                // 找到JPEG格式和RAW格式的最大分辨率
                StreamConfigurationMap map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
                if (map == null) {
                    continue;
                }

                // 使用Collections和Comparator<Size>, Arrays.asList将数组转成集合
                Size largestJpeg = Collections.max(Arrays.asList(map.getOutputSizes(ImageFormat.JPEG)), new CameraUtil.CompareSizesByArea());

                synchronized (mCameraStateLock) {
                    // Camera2抓图需要ImageReader, 避免ImageReader在写文件的时候, 其他流程把资源关闭, 采用RefCountedAutoCloseable包装ImageReader
                    if (mJpegImageReader == null || mJpegImageReader.getAndRetain() == null) {
                        mJpegImageReader = new CameraUtil.RefCountedAutoCloseable<>(ImageReader.newInstance(largestJpeg.getWidth(),
                                largestJpeg.getHeight(), ImageFormat.JPEG, 5));
                    }
                    mJpegImageReader.get().setOnImageAvailableListener(mOnJpegImageAvailableListener, mBackgroundHandler);

                    // 保存可用的camera和对应的characteristics
                    mCharacteristics = characteristics;
                    mCameraId = cameraId;
                }
                return true;
            }
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }

        //TODO toast
        Log.e(TAG, "prepareCamera: This device doesn't support capturing RAW photos");
        return false;
    }

    /**
     * Tells whether all the necessary permissions are granted to this app.
     *
     * @return True if all the required permissions are granted.
     */
    private boolean hasAllPermissionGranted() {
        for (String permission : CAMERA_PERMISSIONS) {
            FragmentActivity activity = getActivity();
            if (activity == null) {
                return false;
            }
            if (ActivityCompat.checkSelfPermission(getActivity(), permission) != PackageManager.PERMISSION_GRANTED) {
                return false;
            }
        }
        return true;
    }

    // 申请运行时权限
    /**
     * Requests permissions necessary to use camera and save pictures.
     */
    private void requestCameraPermissions() {
        // TODO 自定义 Dialog
        requestPermissions(CAMERA_PERMISSIONS, REQUEST_CAMERA_PERMISSIONS);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        if (REQUEST_CAMERA_PERMISSIONS == requestCode) {
            for (int result : grantResults) {
                if (result != PackageManager.PERMISSION_GRANTED) {
                    // TODO Toast
                    FragmentActivity activity = getActivity();
                    if (activity != null) {
                        activity.finish();
                    }
                    return;
                }
            }
        } else {
            super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        }
    }

    // 关闭Camera
    private void closeCamera() {
        synchronized (mCameraStateLock) {
            if (mCaptureSession != null) {
                // 在CameraCaptureSession.StateCallback#onConfigured中创建
                mCaptureSession.close();
                mCaptureSession = null;
            }
            if (mCameraDevice != null) {
                mCameraDevice.close();
                mCameraDevice = null;
            }
        }
    }

    // 打开Camera
    /**
     * Opens the camera specified by {@link #mCameraId}.
     */
    @SuppressLint("MissingPermission")
    private void openCamera() {
        FragmentActivity activity = getActivity();
        if (activity == null) {
            Log.e(TAG, "openCamera: error");
            return;
        }
        CameraManager manager = (CameraManager) activity.getSystemService(Context.CAMERA_SERVICE);
        try {
            String cameraId;
            Handler backgroundHandler;
            synchronized (mCameraStateLock) {
                cameraId = mCameraId;
                backgroundHandler = mBackgroundHandler;
            }

            // Attempt to open the camera. mStateCallback will be called on the background handler's
            // thread when this succeeds or fails.
            manager.openCamera(cameraId, mStateCallback, mBackgroundHandler);
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
    }

    /**
     * Configure the necessary {@link android.graphics.Matrix} transformation to `mTextureView`,
     * and start/restart the preview capture session if necessary.
     *
     * This method should be called after the camera state has been initialized in prepareCamera.
     *
     * @param viewWidth  The width of `mTextureView`
     * @param viewHeight The height of `mTextureView`
     */
    private void confirgureTransform(int viewWidth, int viewHeight) {
        FragmentActivity activity = getActivity();
        synchronized (mCameraStateLock) {
            if (null == mTextureView || null == activity) {
                return;
            }

            StreamConfigurationMap map = mCharacteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            if (map == null) {
                return;
            }

            // 获取预期的ratio
            Size ratio = getJpegRatio(map);
            if (ratio == null) {
                return;
            }

            // 获取屏幕的物理Size
            Point displaySize = getDisplaySize(activity);

            // 获取屏幕的方向, 返回类型有
            /**
             * {@link Surface#ROTATION_0} {@link Surface#ROTATION_90} {@link Surface#ROTATION_180} {@link Surface#ROTATION_270}
             */
            int deviceRotation = getDisplayRotation(activity);

            // TODO 因为camera默认是横着的, 所以这里需要极其繁琐的计算, 涉及device的方向, cameraSensor的方向, camera抓图的size, 屏幕的size等

            // 找到Camera支持的TextureViewSize
            Size[] outputSizes = map.getOutputSizes(SurfaceTexture.class);
            // 找出最适合的outputSize作为previewSize, 用做view尺寸和JPEG尺寸
            // 1. 该尺寸一定要小于等于TextureView的限制尺寸
            // 2. 该尺寸一定要和largestJpeg成比例
            // 3. 该尺寸好是小于等于TextureViewSize的最大值
            // 4. 如果都大于TextureViewSize的话, 就挑一个最小值
            // 5. 如果没有和largestJpeg成比例的, 就选集合里边的第一个Size, 有点自暴自弃的意思

            // 根据横竖屏, 制作出对应的ratio给TextureView, 是的TextureView在横竖屏时, 预览窗口都不变
            int ratioWidth = 0;
            int ratioHeight = 0;
            if (deviceRotation == Surface.ROTATION_90 || deviceRotation == Surface.ROTATION_270) {
                // 横屏
                ratioWidth = ratio.getWidth();
                ratioHeight = ratio.getHeight();
            } else {
                // 竖屏
                ratioWidth = ratio.getHeight();
                ratioHeight = ratio.getWidth();
            }
            mTextureView.setAspectRatio(ratioWidth, ratioHeight);

            // 横屏时, TextureView预览会发现图像被旋转了, 需要用一个Matrix来纠正预览图, 包换旋转与缩放
            Matrix matrix = new Matrix();
            RectF viewRect = new RectF(0, 0, viewWidth, viewHeight);
            // 这里就很讲究了, 为什么要用360减去设备的旋转角, 而不是直接用设备的旋转角, 是因为, 测试验证如果直接用设备的旋转角, 画面是倒过来的
            int degrees = 360;
            if (deviceRotation == Surface.ROTATION_90) {
                degrees -= 90;
            } else if (deviceRotation == Surface.ROTATION_270) {
                degrees -= 270;
            }
            // 假设我们用眼睛观察的图像是
            // A--------B
            // |        |
            // |        |
            // D--------C
            if (deviceRotation == Surface.ROTATION_90 || deviceRotation == Surface.ROTATION_270) {
                // 目前为止, 我们看到的是方向旋转90且变形的预览图
                // B----------C
                // |          |
                // A----------D
                // 参考google demo的写法, 原理不是很清楚. 以下是自己的理解:
                RectF bufferRect = new RectF(0, 0, PREFER_PREVIEW_HEIGHT, PREFER_PREVIEW_WIDTH);
                bufferRect.offset(viewRect.centerX() - bufferRect.centerX(), viewRect.centerY() - bufferRect.centerY());
                matrix.setRectToRect(viewRect, bufferRect, Matrix.ScaleToFit.FILL);
                // 进行不等比缩小, 使得图像不变形
                // B----C
                // |    |
                // A----D
                float scale = Math.max(
                        (float) viewHeight / PREFER_PREVIEW_HEIGHT,
                        (float) viewWidth / PREFER_PREVIEW_WIDTH);
                matrix.postScale(scale, scale, viewRect.centerX(), viewRect.centerY());
                // 进行等比放大, 使得图像的面积与TextView的面积相等, 并且保持图像不变形
                // B--------C
                // |        |
                // |        |
                // A--------D
            }
            matrix.postRotate(degrees, viewRect.centerX(), viewRect.centerY());
            // 进行旋转, 使得图像跟真实场景一样
            // A--------B
            // |        |
            // |        |
            // D--------C
            mTextureView.setTransform(matrix);

            if (mState != STATE_CLOSED) {
                createCameraPreviewSessionLocked();
            }
        }
    }

    // 预览操作
    /**
     * Create a new {@link android.hardware.camera2.CameraCaptureSession} for camera preview.
     *
     * Call this only with {@link #mCameraStateLock} held.
     */
    private void createCameraPreviewSessionLocked() {
        try {
            // 与Camera创建会话createCaptureSession, 要说明会话中有哪些surface, 譬如预览和抓图
            SurfaceTexture surfaceTexture = mTextureView.getSurfaceTexture();
            surfaceTexture.setDefaultBufferSize(PREFER_PREVIEW_WIDTH, PREFER_PREVIEW_HEIGHT);
            final Surface surface = new Surface(surfaceTexture);
            mCameraDevice.createCaptureSession(Arrays.asList(surface, mJpegImageReader.get().getSurface()), new CameraCaptureSession.StateCallback() {
                @Override
                public void onConfigured(@NonNull CameraCaptureSession session) {
                    synchronized (mCameraStateLock) {
                        // 配置成功
                        if (null == mCameraDevice) {
                            // 相机已经关闭
                            return;
                        }

                        try {
                            // 会话连接成功后, 构建预览请求, 添加目标surface
                            mPreviewCaptureRequest = mCameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
                            mPreviewCaptureRequest.addTarget(surface);
                            // 利用会话发送预览请求
                            session.setRepeatingRequest(mPreviewCaptureRequest.build(), null, mBackgroundHandler);
                            // CameraCaptureSession可用来截图
                            mCaptureSession = session;
                            mState = STATE_PREVIEW;
                        } catch (CameraAccessException e) {
                            e.printStackTrace();
                        }
                    }
                }

                @Override
                public void onConfigureFailed(@NonNull CameraCaptureSession session) {
                    Log.e(TAG, "onConfigureFailed: ");
                }
            }, mBackgroundHandler);

        } catch (CameraAccessException e) {
            Log.e(TAG, "createCameraPreviewSessionLocked: CameraAccessException");
            e.printStackTrace();
        }
    }

    @Override
    public void onClick(View v) {
        switch (v.getId()) {
            case R.id.picture_btn:
                takePicture();
                break;
        }
    }

    // 抓图操作
    /**
     * Initiate a still image capture.
     *
     * This function sends a capture request that initiates a pre-capture sequence in our state
     * machine that waits for auto-focus to finish, ending in a "locked" state where the len is no
     * longer moving, waits for auto-exposure to choose a good exposure value, and waits for
     * auto-white-balance to converge.
     */
    private void takePicture() {
        synchronized (mCameraStateLock) {
            mPendingUserCaptures++;

            // If we already triggered a pre-capture sequence, or are in a state where we cannot do
            // this, return immediately.
            if (mState != STATE_PREVIEW) {
                return;
            }

            // 在预览请求的基础上, 添加自动对焦, 自动曝光, 自动白平衡请求
            // 添加AF请求
            mPreviewCaptureRequest.set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_START);

            // 添加AE请求
            mPreviewCaptureRequest.set(CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER, CameraMetadata.CONTROL_AE_PRECAPTURE_TRIGGER_START);

            // 添加AWB请求
            mPreviewCaptureRequest.set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO);

            // Update state machine to wait for auto-focus, auto-exposure, and auto-white-balance (aka. "3A") to converge.
            mState = STATE_WAITING_FOR_3A_CONVERGENCE;

            // Start a timer for the pre-capture sequence.
            startTimerLocked();

            try {
                // Replace the existing repeating request with one with updated 3A triggers.
                // 替代原先仅仅预览的请求, 向会话发出预览加3A的请求
                mCaptureSession.capture(mPreviewCaptureRequest.build(), mPreCaptureCallback, mBackgroundHandler);
            } catch (CameraAccessException e) {
                e.printStackTrace();
            }
        }
    }

    /**
     * Start the timer for the pre-capture sequence.
     *
     * Call this only with {@link #mCameraStateLock} held.
     */
    private void startTimerLocked() {
        mCaptureTimer = SystemClock.elapsedRealtime();
    }

    /**
     * Check if the timer for the pre-capture sequence has been hit.
     *
     * Call this only with {@link #mCameraStateLock} held.
     *
     * @return true if the timeout occurred.
     */
    private boolean hitTimeoutLocked() {
        return (SystemClock.elapsedRealtime() - mCaptureTimer) > PRECAPTURE_TIMEOUT_MS;
    }

    /**
     * Send a capture request to the camera device that initiates a capture targeting the JPEG outputs.
     *
     * Call this only with {@link #mCameraStateLock} held.
     */
    private void captureStillPictureLocked() {
        try {
            final Activity activity = getActivity();
            if (null == activity || null == mCameraDevice) {
                return;
            }

            // 创建新的请求, 用来抓图
            // This is the CaptureRequest.Builder that we use to take a picture.
            final CaptureRequest.Builder captureBuilder = mCameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);

            // 要添加ImageReader的Surface作为Target
            captureBuilder.addTarget(mJpegImageReader.get().getSurface());

            // 旋转保证抓图与预览同方向
            int rotation = activity.getWindowManager().getDefaultDisplay().getRotation();
            captureBuilder.set(CaptureRequest.JPEG_ORIENTATION, sensorToDeviceRotation(mCharacteristics, rotation));

            CaptureRequest request = captureBuilder.build();

            // 向会话发送抓图请求
            mCaptureSession.capture(request, null, mBackgroundHandler);

        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
    }

    private boolean dumpJpegToFile(File jpegFile, Image image) {
        boolean result = true;
        ByteBuffer buffer = image.getPlanes()[0].getBuffer();
        byte[] bytes = new byte[buffer.remaining()];
        if (buffer.remaining() == 0) {
            result = false;
        }
        buffer.get(bytes);
        FileOutputStream output = null;
        try {
            output = new FileOutputStream(jpegFile);
            output.write(bytes);
        } catch (IOException e) {
            e.printStackTrace();
            result = false;
        } finally {
            image.close();
            if (output != null) {
                try {
                    output.close();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        }
        return result;
    }

    // 录制操作
    @Override
    public boolean onLongClick(View v) {
        switch (v.getId()) {
            case R.id.picture_btn:
                synchronized (mCameraStateLock) {
                    if (!mRecording) {
                        mRecording = true;
                        startRecordingVideoLocked();
                    }
                }
                return true;
        }
        return false;
    }

    @Override
    public boolean onTouch(View v, MotionEvent event) {
        switch (v.getId()) {
            case R.id.picture_btn:
                switch (event.getAction()) {
                    case MotionEvent.ACTION_CANCEL:
                        synchronized (mCameraStateLock) {
                            if (mRecording) {
                                mRecording = false;
                                // 松手关闭录制重启预览
                                stopRecordingVideoLocked();
                            }
                        }
                        break;    
                }
                break;
        }
        return false;
    }

    private void startRecordingVideoLocked() {
        if (null == mCameraDevice || !mTextureView.isAvailable()) {
            return;
        }

        try {
            // 关闭预览会话
            closePreviewSessionLocked();
            // 准备MediaRecorder用来编码和封装
            setUpMediaRecorder();

            // 创建录制请求
            SurfaceTexture surfaceTexture = mTextureView.getSurfaceTexture();
            surfaceTexture.setDefaultBufferSize(PREFER_PREVIEW_WIDTH, PREFER_PREVIEW_HEIGHT);
            mRecodeBuilder = mCameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_RECORD);
            List<Surface> surfaces = new ArrayList<>();

            // Set up Surface for the camera preview.
            Surface previewSurface = new Surface(surfaceTexture);
            surfaces.add(previewSurface);
            mRecodeBuilder.addTarget(previewSurface);

            // Set up Surface for the MediaRecorder.
            Surface recorderSurface = mMediaRecorder.getSurface();
            surfaces.add(recorderSurface);
            mRecodeBuilder.addTarget(recorderSurface);

            // Start a capture session
            // Once the session starts, we can update the UI and start recording.
            // 创建录制会话
            mCameraDevice.createCaptureSession(surfaces, new CameraCaptureSession.StateCallback() {
                @Override
                public void onConfigured(@NonNull CameraCaptureSession session) {
                    synchronized (mCameraStateLock) {
                        mRecordSession = session;
                        // 向录制会话发送录制请求
                        updatePreviewLocked();
                        getActivity().runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                // Start recording
                                // 启动MediaRecorder
                                mMediaRecorder.start();
                            }
                        });
                    }
                }

                @Override
                public void onConfigureFailed(@NonNull CameraCaptureSession session) {

                }
            }, mBackgroundHandler);

        } catch (CameraAccessException e) {
            e.printStackTrace();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private void updatePreviewLocked() {
        mRecodeBuilder.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO);
        try {
            mRecordSession.setRepeatingRequest(mRecodeBuilder.build(), null, mBackgroundHandler);
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
    }

    private void setUpMediaRecorder() throws IOException {
        final Activity activity = getActivity();
        if (null == activity) {
            return;
        }
        if (null == mMediaRecorder) {
            mMediaRecorder = new MediaRecorder();
        }
        mMediaRecorder.setAudioSource(MediaRecorder.AudioSource.MIC);
        mMediaRecorder.setVideoSource(MediaRecorder.VideoSource.SURFACE);
        mMediaRecorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
        if (mNextVideoAbsolutePath == null || mNextVideoAbsolutePath.isEmpty()) {
            mNextVideoAbsolutePath = getMp4FilePath(getActivity());
        }
        mMediaRecorder.setOutputFile(mNextVideoAbsolutePath);
        mMediaRecorder.setVideoEncodingBitRate(10_000_000);
        mMediaRecorder.setVideoFrameRate(30);
        mMediaRecorder.setVideoSize(PREFER_RECORD_WIDTH, PREFER_RECORD_HEIGHT);
        mMediaRecorder.setVideoEncoder(MediaRecorder.VideoEncoder.H264);
        mMediaRecorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
        int rotation = getActivity().getWindowManager().getDefaultDisplay().getRotation();
        mSensorOrientation = mCharacteristics.get(CameraCharacteristics.SENSOR_ORIENTATION);
        switch (mSensorOrientation) {
            case SENSOR_ORIENTATION_DEFAULT_DEGREES:
                mMediaRecorder.setOrientationHint(DEFAULT_ORIENTATIONS.get(rotation));
                break;
            case SENSOR_ORIENTATION_INVERSE_DEGREES:
                mMediaRecorder.setOrientationHint(INVERSE_ORIENTATIONS.get(rotation));
                break;
        }
        mMediaRecorder.prepare();
    }

    private void closePreviewSessionLocked() {
        if (mCaptureSession != null) {
            mCaptureSession.close();
            mCaptureSession = null;
        }
    }

    private void stopRecordingVideoLocked() {
        // Stop recording
        // 释放MediaRecorder
        if (null != mMediaRecorder) {
            mMediaRecorder.stop();
            mMediaRecorder.reset();
            mMediaRecorder.release();
            mMediaRecorder = null;

            Log.e(TAG, "stopRecordingVideoLocked: Video saved: " + mNextVideoAbsolutePath);
            mNextVideoAbsolutePath = null;
        }

        // 关闭录制会话
        closeRecordSessionLocked();

        // 重新开始预览会话和发送预览请求
        startPreviewLocked();
    }

    private void startPreviewLocked() {
        createCameraPreviewSessionLocked();
    }

    private void closeRecordSessionLocked() {
        if (mRecordSession != null) {
            mRecordSession.close();
            mRecordSession = null;
        }
    }
}
