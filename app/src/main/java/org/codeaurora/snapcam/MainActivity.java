package org.codeaurora.snapcam;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.ImageFormat;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CameraMetadata;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.TotalCaptureResult;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.Image;
import android.media.ImageReader;
import android.media.MediaRecorder;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;
import android.util.SparseIntArray;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.widget.Button;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import androidx.core.content.ContextCompat;
import android.os.Build;
import java.util.Comparator;
import java.util.Collections;
import android.util.Size;
import java.text.SimpleDateFormat;
import java.util.Date;
import android.util.Range;
import android.hardware.camera2.CameraConstrainedHighSpeedCaptureSession;
import android.widget.TextView;
import android.widget.ImageButton;
import android.graphics.Rect;
import androidx.annotation.Nullable;
import androidx.core.math.MathUtils;

public class MainActivity extends AppCompatActivity {
    private static final String TAG = "AndroidCameraApi";
    private ImageButton takePictureButton;
    private TextureView textureView;
	private TextView tv;
    private static final SparseIntArray ORIENTATIONS = new SparseIntArray();

    static {
        ORIENTATIONS.append(Surface.ROTATION_0, 90);
        ORIENTATIONS.append(Surface.ROTATION_90, 0);
        ORIENTATIONS.append(Surface.ROTATION_180, 270);
        ORIENTATIONS.append(Surface.ROTATION_270, 180);
    }


	private static class CompareSizeByArea implements Comparator<Size> {

		@Override
		public int compare (Size ths, Size rhs)
		{ return Long.signum((long) ths.getWidth() * ths.getHeight() /
							 (long) rhs.getWidth() * rhs.getHeight());
		}
	}
	//Zoom
	public final class Zoom
	{
		private static final float DEFAULT_ZOOM_FACTOR = 1.0f;

		@NonNull
		private final Rect mCropRegion = new Rect();

		public final float maxZoom;

		@Nullable
		private final Rect mSensorSize;

		public final boolean hasSupport;

		public Zoom(@NonNull final CameraCharacteristics characteristics)
		{
			this.mSensorSize = characteristics.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE);

			if (this.mSensorSize == null)
			{
				this.maxZoom = Zoom.DEFAULT_ZOOM_FACTOR;
				this.hasSupport = false;
				return;
			}

			final Float value = characteristics.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM);

			this.maxZoom = ((value == null) || (value < Zoom.DEFAULT_ZOOM_FACTOR))
                ? Zoom.DEFAULT_ZOOM_FACTOR
                : value;

			this.hasSupport = (Float.compare(this.maxZoom, Zoom.DEFAULT_ZOOM_FACTOR) > 0);
		}
		
		public void setZoom(@NonNull final CaptureRequest.Builder builder, final float zoom)
		{
			if (this.hasSupport == false)
			{
				return;
			}

			final float newZoom = MathUtils.clamp(zoom, Zoom.DEFAULT_ZOOM_FACTOR, this.maxZoom);

			final int centerX = this.mSensorSize.width() / 2;
			final int centerY = this.mSensorSize.height() / 2;
			final int deltaX  = (int)((0.5f * this.mSensorSize.width()) / newZoom);
			final int deltaY  = (int)((0.5f * this.mSensorSize.height()) / newZoom);

			this.mCropRegion.set(centerX - deltaX,
								 centerY - deltaY,
								 centerX + deltaX,
								 centerY + deltaY);

			builder.set(CaptureRequest.SCALER_CROP_REGION, this.mCropRegion);
		}
	}
	
	
    private String cameraId;
	private String mCameraId;
	private String str = "0";
	Range<Integer> fps = new Range<>(110,120);
	private Range<Integer>[] availableFpsRange;
	private Size mPreviewSize;
	private Size mVideoSize;
	private int totalRotation;
    protected CameraDevice cameraDevice;
    protected CameraCaptureSession cameraCaptureSessions;
    protected CaptureRequest captureRequest;
    protected CaptureRequest.Builder mCaptureRequestBuilder;
    private Size imageDimension;
    private ImageReader imageReader;
	private boolean isRecording = false;
    private File file;
	private File fileFolder;
	File videoFile;
    private static final int REQUEST_CAMERA_PERMISSION = 200;
    private boolean mFlashSupported;
    private Handler mBackgroundHandler;
    private HandlerThread mBackgroundThread;
	private MediaRecorder mMediaRecorder;
	private TextureView mTextureView;
	private CaptureRequest.Builder captureRequestBuilder;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

		createFolder();
		mMediaRecorder = new MediaRecorder();

		tv = findViewById(R.id.tv);
        textureView = (TextureView) findViewById(R.id.texture);
        assert textureView != null;
        textureView.setSurfaceTextureListener(textureListener);

        takePictureButton = (ImageButton) findViewById(R.id.btn_takepicture);
        assert takePictureButton != null;
        takePictureButton.setOnClickListener(new View.OnClickListener() {
				@Override
				public void onClick(View v) {
					if(isRecording == false){
						isRecording = true;
						try {
							createFileName();
						} catch (IOException e) {}
						startRecord();
						mMediaRecorder.start();
						takePictureButton.setImageResource(R.drawable.capture_button_video_background);
					}
					else if(isRecording){
						isRecording = false;
						mMediaRecorder.stop();
						mMediaRecorder.reset();
						startPreview();
						takePictureButton.setImageResource(R.drawable.capture_button_video);
					}
				}
			});
    }
//Surface Texture
    TextureView.SurfaceTextureListener textureListener = new TextureView.SurfaceTextureListener() {
        @Override
        public void onSurfaceTextureAvailable(SurfaceTexture surface, int width, int height) {
            //open your camera here
            setupCamera(width,height);
			connectCamera();
			startPreview();
        }

        @Override
        public void onSurfaceTextureSizeChanged(SurfaceTexture surface, int width, int height) {
            // Transform you image captured size according to the surface width and height
			closeCamera();
			setupCamera(width,height);
			connectCamera();
			startPreview();
        }

        @Override
        public boolean onSurfaceTextureDestroyed(SurfaceTexture surface) {
            return false;
        }

        @Override
        public void onSurfaceTextureUpdated(SurfaceTexture surface) {
        }
    };
	private CameraDevice mCameraDevice;
	// Camera Device State Callback
    private CameraDevice.StateCallback mStateCallback = new CameraDevice.StateCallback() {
        @Override
        public void onOpened(CameraDevice camera) {
            //This is called when the camera is open
            Log.e(TAG, "onOpened");
			if(isRecording){
				try {
					createFileName();
				} catch (IOException e) {}
				startRecord();
				mMediaRecorder.start();
			}
			mCameraDevice = camera;
        }

        @Override
        public void onDisconnected(CameraDevice camera) {
            cameraDevice.close();
        }

        @Override
        public void onError(CameraDevice camera, int error) {
            cameraDevice.close();
            cameraDevice = null;
        }
    };
	/*
	 final CameraCaptureSession.CaptureCallback captureCallbackListener = new CameraCaptureSession.CaptureCallback() {
	 @Override
	 public void onCaptureCompleted(CameraCaptureSession session, CaptureRequest request, TotalCaptureResult result) {
	 super.onCaptureCompleted(session, request, result);
	 Toast.makeText(MainActivity.this, "Saved:" + file, Toast.LENGTH_SHORT).show();
	 createCameraPreview();
	 }
	 };
	 */
//Background Thread
    protected void startBackgroundThread() {
        mBackgroundThread = new HandlerThread("Camera Background");
        mBackgroundThread.start();
        mBackgroundHandler = new Handler(mBackgroundThread.getLooper());
    }

    protected void stopBackgroundThread() {
        mBackgroundThread.quitSafely();
        try {
            mBackgroundThread.join();
            mBackgroundThread = null;
            mBackgroundHandler = null;
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }
	// Setup Camera
	private void setupCamera(int width, int height){
		CameraManager cameraManager =(CameraManager) getSystemService(Context.CAMERA_SERVICE);
		
		try {
			for (String cameraId: cameraManager.getCameraIdList()) {
				tv.append(cameraId + "\n");
			}
		} catch (CameraAccessException e) {}
		
		try {
		//	for (String cameraId: cameraManager.getCameraIdList()) {
				CameraCharacteristics cameraCharacteristics = cameraManager.getCameraCharacteristics(str);
			/*	if(cameraCharacteristics.get(cameraCharacteristics.LENS_FACING ) == cameraCharacteristics.LENS_FACING_FRONT){
					continue;
				}
			*/
				StreamConfigurationMap map = cameraCharacteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
				int deviceOrientation = getWindowManager().getDefaultDisplay().getRotation();
				totalRotation = sensorToDeviceRotation(cameraCharacteristics,deviceOrientation);
				boolean swapRotation = totalRotation == 90 || totalRotation == 270;
				int rotatedWidth = width;
				int rotatedHeight = height;
				if(swapRotation){
					rotatedWidth = height;
					rotatedHeight = width;
				}
				mPreviewSize = chooseOptimalSize(map.getOutputSizes(SurfaceTexture.class),rotatedWidth,rotatedHeight);
				mVideoSize = chooseOptimalSize(map.getOutputSizes(MediaRecorder.class),rotatedWidth,rotatedHeight);
				mCameraId = str;
				//mCameraId=cameraId;
				/*
				 // FPS
				 availableFpsRange = map.getHighSpeedVideoFpsRangesFor(mVideoSize);
				 int max = 0;
				 int min;
				 for (Range<Integer> r : availableFpsRange) {
				 if (max < r.getUpper()) {
				 max = r.getUpper();
				 }
				 }
				 min = max;
				 for (Range<Integer> r : availableFpsRange) {
				 if (min > r.getLower()) {
				 min = r.getUpper();
				 }
				 }
				 //            for(Range<Integer> r: availableFpsRange) {
				 //                if(min == r.getLower() && max == r.getUpper()) {
				 //                     mPreviewBuilder.set(CONTROL_AE_TARGET_FPS_RANGE,r);
				 //                    Log.d("RANGES", "[ " + r.getLower() + " , " + r.getUpper() + " ]");
				 //                }
				 //            }
				 */
				return;
			//}
		} catch (CameraAccessException e) {}
	}
	//Connect Camera
	private void connectCamera(){
		CameraManager cameraManager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
		try{
			if (ActivityCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED && ActivityCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
				ActivityCompat.requestPermissions(MainActivity.this, new String[]{Manifest.permission.CAMERA, Manifest.permission.WRITE_EXTERNAL_STORAGE}, REQUEST_CAMERA_PERMISSION);
				return;
			}
			cameraManager.openCamera(mCameraId, mStateCallback, mBackgroundHandler);
		} catch (CameraAccessException e) {
			e.printStackTrace();
		}
	}

	private void startRecordingVideo() {
        try {
            // UI

            setupMediaRecorder();
            SurfaceTexture texture = mTextureView.getSurfaceTexture();
            assert texture != null;
            texture.setDefaultBufferSize(mPreviewSize.getWidth(), mPreviewSize.getHeight());
            mCaptureRequestBuilder = mCameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_RECORD);
//            List<Surface> surfaces = new ArrayList<>();
            Surface previewSurface = new Surface(texture);
            mCaptureRequestBuilder.addTarget(previewSurface);
            Surface recorderSurface = mMediaRecorder.getSurface();
			mCaptureRequestBuilder.addTarget(previewSurface);
            mCaptureRequestBuilder.addTarget(recorderSurface);

            Log.d("FPS", CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE.toString());

            mCameraDevice.createConstrainedHighSpeedCaptureSession(Arrays.asList(previewSurface,recorderSurface),
				new CameraCaptureSession.StateCallback() {
					@Override
					public void onConfigured(@NonNull CameraCaptureSession cameraCaptureSession) {
						try {
							cameraCaptureSession.setRepeatingRequest(mCaptureRequestBuilder.build(), null, mBackgroundHandler);
						} catch (CameraAccessException e) {}
					}

					@Override
					public void onConfigureFailed(@NonNull CameraCaptureSession cameraCaptureSession) {
						Toast.makeText(MainActivity.this, "Failed", Toast.LENGTH_SHORT).show();
					}
				}, mBackgroundHandler);
            // Start recording
            mMediaRecorder.start();

        } catch (IllegalStateException | IOException | CameraAccessException e) {
            e.printStackTrace();
        }
    }

	//start Record
	private void startRecord(){
		try {
			setupMediaRecorder();
		} catch (IOException e) {}
		SurfaceTexture surfaceTexture = textureView.getSurfaceTexture();
		surfaceTexture.setDefaultBufferSize(mPreviewSize.getWidth(),mPreviewSize.getHeight());
		Surface previewSurface = new Surface(surfaceTexture);
		Surface recordSurface = mMediaRecorder.getSurface();
		//Range<Integer> fpsRange = getHighestFpsRange(availableFpsRange);
		try {
			mCaptureRequestBuilder = mCameraDevice.createCaptureRequest(cameraDevice.TEMPLATE_PREVIEW);
			mCaptureRequestBuilder.addTarget(previewSurface);
			mCaptureRequestBuilder.addTarget(recordSurface);
			captureRequestBuilder.set(captureRequest.CONTROL_MODE,CameraMetadata.CONTROL_MODE_AUTO);
			captureRequestBuilder.set(captureRequest.CONTROL_VIDEO_STABILIZATION_MODE,CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_ON);
			//captureRequestBuilder.set(captureRequest.CONTROL_AE_TARGET_FPS_RANGE,fpsRange);
			mCameraDevice.createCaptureSession(Arrays.asList(previewSurface,recordSurface),
				new CameraCaptureSession.StateCallback(){
					@Override
					public void onConfigured(CameraCaptureSession session) {
						try {
							session.setRepeatingRequest(mCaptureRequestBuilder.build(), null, mBackgroundHandler);
						} catch (CameraAccessException e) {}

					}

					@Override
					public void onConfigureFailed(CameraCaptureSession session) {
						Toast.makeText(MainActivity.this, "Configuration change", Toast.LENGTH_SHORT).show();
					}
				}, null);

		} catch (CameraAccessException e) {}
	}

	//  Start Preview
	private void startPreview(){
		SurfaceTexture surfaceTexture = textureView.getSurfaceTexture();
		surfaceTexture.setDefaultBufferSize(mPreviewSize.getWidth(),mPreviewSize.getHeight());
		Surface previewSurface = new Surface(surfaceTexture);

		try {
			captureRequestBuilder = mCameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
			captureRequestBuilder.addTarget(previewSurface);
			captureRequestBuilder.set(captureRequest.CONTROL_MODE,CameraMetadata.CONTROL_MODE_AUTO);
			captureRequestBuilder.set(captureRequest.CONTROL_VIDEO_STABILIZATION_MODE,CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_ON);
			
			mCameraDevice.createCaptureSession(Arrays.asList(previewSurface),
				new CameraCaptureSession.StateCallback() {
					@Override
					public void onConfigured(@NonNull CameraCaptureSession cameraCaptureSession) {
						try {
							cameraCaptureSession.setRepeatingRequest(captureRequestBuilder.build(), null, mBackgroundHandler);
						} catch (CameraAccessException e) {}
					}

					@Override
					public void onConfigureFailed(@NonNull CameraCaptureSession cameraCaptureSession) {
						Toast.makeText(MainActivity.this, "Configuration change", Toast.LENGTH_SHORT).show();
					}
				}, null);

		} catch (CameraAccessException e) {}
	}

	//Take Picture
    protected void takePicture() {
        if (null == cameraDevice) {
            Log.e(TAG, "cameraDevice is null");
            return;
        }
        CameraManager manager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
        try {
            CameraCharacteristics characteristics = manager.getCameraCharacteristics(cameraDevice.getId());
            Size[] jpegSizes = null;
            if (characteristics != null) {
                jpegSizes = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP).getOutputSizes(ImageFormat.JPEG);
            }
            int width = 640;
            int height = 480;
            if (jpegSizes != null && 0 < jpegSizes.length) {
                width = jpegSizes[0].getWidth();
                height = jpegSizes[0].getHeight();
            }
            ImageReader reader = ImageReader.newInstance(width, height, ImageFormat.JPEG, 1);
            List<Surface> outputSurfaces = new ArrayList<Surface>(2);
            outputSurfaces.add(reader.getSurface());
            outputSurfaces.add(new Surface(textureView.getSurfaceTexture()));
            final CaptureRequest.Builder captureBuilder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);
            captureBuilder.addTarget(reader.getSurface());
            captureBuilder.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO);
            // Orientation
            int rotation = getWindowManager().getDefaultDisplay().getRotation();
            captureBuilder.set(CaptureRequest.JPEG_ORIENTATION, ORIENTATIONS.get(rotation));
            final File file = new File(Environment.getExternalStorageDirectory() + "/DCIM/pic.jpg");
            ImageReader.OnImageAvailableListener readerListener = new ImageReader.OnImageAvailableListener() {
                @Override
                public void onImageAvailable(ImageReader reader) {
                    Image image = null;
                    try {
                        image = reader.acquireLatestImage();
                        ByteBuffer buffer = image.getPlanes()[0].getBuffer();
                        byte[] bytes = new byte[buffer.capacity()];
                        buffer.get(bytes);
                        save(bytes);
                    } catch (FileNotFoundException e) {
                        e.printStackTrace();
                    } catch (IOException e) {
                        e.printStackTrace();
                    } finally {
                        if (image != null) {
                            image.close();
                        }
                    }
                }

                private void save(byte[] bytes) throws IOException {
                    OutputStream output = null;
                    try {
                        output = new FileOutputStream(file);
                        output.write(bytes);
                    } finally {
                        if (null != output) {
                            output.close();
                        }
                    }
                }
            };
            reader.setOnImageAvailableListener(readerListener, mBackgroundHandler);
            final CameraCaptureSession.CaptureCallback captureListener = new CameraCaptureSession.CaptureCallback() {
                @Override
                public void onCaptureCompleted(CameraCaptureSession session, CaptureRequest request, TotalCaptureResult result) {
                    super.onCaptureCompleted(session, request, result);
                    Toast.makeText(MainActivity.this, "Saved:" + file, Toast.LENGTH_SHORT).show();
                    createCameraPreview();
                }
            };
            cameraDevice.createCaptureSession(outputSurfaces, new CameraCaptureSession.StateCallback() {
					@Override
					public void onConfigured(CameraCaptureSession session) {
						try {
							session.capture(captureBuilder.build(), captureListener, mBackgroundHandler);
						} catch (CameraAccessException e) {
							e.printStackTrace();
						}
					}

					@Override
					public void onConfigureFailed(CameraCaptureSession session) {
					}
				}, mBackgroundHandler);
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
    }

    protected void createCameraPreview() {
        try {
            SurfaceTexture texture = textureView.getSurfaceTexture();
            assert texture != null;
            texture.setDefaultBufferSize(imageDimension.getWidth(), imageDimension.getHeight());
            Surface surface = new Surface(texture);
            captureRequestBuilder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            captureRequestBuilder.addTarget(surface);
            cameraDevice.createCaptureSession(Arrays.asList(surface), new CameraCaptureSession.StateCallback() {
					@Override
					public void onConfigured(@NonNull CameraCaptureSession cameraCaptureSession) {
						//The camera is already closed
						if (null == cameraDevice) {
							return;
						}
						// When the session is ready, we start displaying the preview.
						cameraCaptureSessions = cameraCaptureSession;
						updatePreview();
					}

					@Override
					public void onConfigureFailed(@NonNull CameraCaptureSession cameraCaptureSession) {
						Toast.makeText(MainActivity.this, "Configuration change", Toast.LENGTH_SHORT).show();
					}
				}, null);
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
    }

    private void openCamera() {
        CameraManager manager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
        Log.e(TAG, "is camera open");
        try {
            cameraId = manager.getCameraIdList()[0];
            CameraCharacteristics characteristics = manager.getCameraCharacteristics(cameraId);
            StreamConfigurationMap map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            assert map != null;
            imageDimension = map.getOutputSizes(SurfaceTexture.class)[0];
            // Add permission for camera and let user grant the permission
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED && ActivityCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(MainActivity.this, new String[]{Manifest.permission.CAMERA, Manifest.permission.WRITE_EXTERNAL_STORAGE}, REQUEST_CAMERA_PERMISSION);
                return;
            }
            manager.openCamera(cameraId, mStateCallback, null);
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
        Log.e(TAG, "openCamera X");
    }

    protected void updatePreview() {
        if (null == cameraDevice) {
            Log.e(TAG, "updatePreview error, return");
        }
        captureRequestBuilder.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO);
        try {
            cameraCaptureSessions.setRepeatingRequest(captureRequestBuilder.build(), null, mBackgroundHandler);
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
    }

	//Setup Media Recorder
	private void setupMediaRecorder() throws IOException {
		/*
		 mMediaRecorder.setAudioSource(MediaRecorder.AudioSource.MIC);
		 mMediaRecorder.setVideoSource(MediaRecorder.VideoSource.SURFACE);
		 mMediaRecorder.setOutputFormat(MediaRecorder.OutputFormat.WEBM);
		 mMediaRecorder.setOutputFile(videoFile);
		 mMediaRecorder.setVideoEncodingBitRate(20000000);
		 mMediaRecorder.setVideoFrameRate(240);
		 mMediaRecorder.setVideoSize(mVideoSize.getWidth(), mVideoSize.getHeight());
		 mMediaRecorder.setVideoEncoder(MediaRecorder.VideoEncoder.VP8);
		 mMediaRecorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
		 mMediaRecorder.setOrientationHint(totalRotation - 90);
		 mMediaRecorder.prepare();
		 */

		mMediaRecorder.setVideoSource(MediaRecorder.VideoSource.SURFACE);
		mMediaRecorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
		mMediaRecorder.setOutputFile(videoFile);
		mMediaRecorder.setVideoEncodingBitRate(20000000);
		mMediaRecorder.setVideoFrameRate(240);
		mMediaRecorder.setVideoSize(1920,1080);
		mMediaRecorder.setVideoEncoder(MediaRecorder.VideoEncoder.H264);
		mMediaRecorder.setOrientationHint(totalRotation - 90);
		mMediaRecorder.prepare();

	}	

    private void closeCamera() {
        if (null != cameraDevice) {
            cameraDevice.close();
            cameraDevice = null;
        }
        if (null != imageReader) {
            imageReader.close();
            imageReader = null;
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        if (requestCode == REQUEST_CAMERA_PERMISSION) {
            if (grantResults[0] == PackageManager.PERMISSION_DENIED) {
                // close the app
                Toast.makeText(MainActivity.this, "Sorry!!!, you can't use this app without granting permission", Toast.LENGTH_LONG).show();
                finish();
            }
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        Log.e(TAG, "onResume");
        startBackgroundThread();
        if (textureView.isAvailable()) {
            setupCamera(textureView.getWidth(),textureView.getHeight());
			connectCamera();
			startPreview();
        } else {
            textureView.setSurfaceTextureListener(textureListener);
        }
    }

    @Override
    protected void onPause() {
        Log.e(TAG, "onPause");
		if(isRecording){
			isRecording = false;
			mMediaRecorder.stop();
			mMediaRecorder.reset();
			takePictureButton.setImageResource(R.drawable.capture_button_video);
		}
        closeCamera();
        stopBackgroundThread();
        super.onPause();
    }

	@Override
	protected void onDestroy() {
		if(isRecording){
			isRecording = false;
			mMediaRecorder.stop();
			mMediaRecorder.reset();
		}
		closeCamera();
		super.onDestroy();
	}
	private static int sensorToDeviceRotation (CameraCharacteristics cameraCharacteristics, int deviceOrientation) {
		int sensorOrienatation = cameraCharacteristics.get(CameraCharacteristics.SENSOR_ORIENTATION);
		deviceOrientation = ORIENTATIONS.get(deviceOrientation);
		return (sensorOrienatation + deviceOrientation + 360) % 360;

	}

	private static Size chooseOptimalSize(Size[] choices, int textureViewWidth,
										  int textureViewHeight) {

        // Collect the supported resolutions that are at least as big as the preview Surface
        List<Size> bigEnough = new ArrayList<>();
        // Collect the supported resolutions that are smaller than the preview Surface

        for (Size option : choices) {
            if (option.getHeight() == option.getWidth() * textureViewHeight / textureViewWidth && 
				option.getWidth() >= textureViewWidth &&
				option.getHeight() >= textureViewHeight) {
				bigEnough.add(option);
			}
		}
        // Pick the smallest of those big enough. If there is no one big enough, pick the
        // largest of those not big enough.
        if (bigEnough.size() > 0) {
            return Collections.min(bigEnough, new CompareSizeByArea());
        } else {
            Log.e(TAG, "Couldn't find any suitable preview size");
            return choices[0];
        }
    }

	private void createFolder(){
		File folder = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM);
		fileFolder = new File(folder,"Camera2");
		if(!fileFolder.exists()){
			fileFolder.mkdirs();
		}
	}

	private File createFileName() throws IOException{
		String timestamp = new SimpleDateFormat("yyyyMMdd_HHmmss").format(new Date());
		videoFile = File.createTempFile(timestamp,".mp4",fileFolder);
		return videoFile;
	}
	//Setup Capture Req Builder
	private void setUpCaptureRequestBuilder(CaptureRequest.Builder builder) {
//        Range<Integer> fpsRange = Range.create(240, 240);
        Range<Integer> fpsRange = getHighestFpsRange(availableFpsRange);
        builder.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, fpsRange);

    }

	//Get Highest FPS Range
	private Range<Integer> getHighestFpsRange(Range<Integer>[] fpsRanges) {
        Range<Integer> fpsRange = Range.create(fpsRanges[0].getLower(), fpsRanges[0].getUpper());
        for (Range<Integer> r : fpsRanges) {
            if (r.getUpper() > fpsRange.getUpper()) {
                fpsRange.extend(0, r.getUpper());
            }
        }

        for (Range<Integer> r : fpsRanges) {
            if (r.getUpper() == fpsRange.getUpper()) {
                if (r.getLower() < fpsRange.getLower()) {
                    fpsRange.extend(r.getLower(), fpsRange.getUpper());
                }
            }
        }
        return fpsRange;
    }

}

