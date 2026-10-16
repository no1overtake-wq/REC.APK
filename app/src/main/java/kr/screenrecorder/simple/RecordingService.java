package kr.screenrecorder.simple;

import android.app.Activity;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.ContentValues;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.Color;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.provider.MediaStore;
import android.util.DisplayMetrics;
import android.view.Surface;
import android.view.WindowManager;

import java.nio.ByteBuffer;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public final class RecordingService extends Service {
    public static final String START = "kr.screenrecorder.simple.START";
    public static final String STOP = "kr.screenrecorder.simple.STOP";
    public static final String RESULT_CODE = "result_code";
    public static final String RESULT_DATA = "result_data";
    private static final String CHANNEL = "recorder_ongoing";
    private static final int NOTIF = 7101;
    public static volatile boolean running = false;
    private volatile boolean stopRequested;
    private Thread worker;

    @Override public IBinder onBind(Intent intent) { return null; }

    @Override public void onCreate() {
        super.onCreate();
        NotificationManager manager = (NotificationManager)getSystemService(NOTIFICATION_SERVICE);
        if (manager != null) {
            NotificationChannel channel = new NotificationChannel(CHANNEL, "화면 녹화", NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("녹화 중 알림과 녹화 종료 버튼");
            channel.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
            manager.createNotificationChannel(channel);
        }
    }
    private Notification notification(String title, String message, boolean showStop) {
        Notification.Builder builder = new Notification.Builder(this, CHANNEL)
                .setSmallIcon(android.R.drawable.presence_video_online)
                .setContentTitle(title)
                .setContentText(message)
                .setColor(Color.rgb(45, 110, 235))
                .setOnlyAlertOnce(true)
                .setOngoing(showStop)
                .setCategory(Notification.CATEGORY_SERVICE)
                .setShowWhen(true);
        if (showStop) {
            Intent action = new Intent(this, RecordingService.class).setAction(STOP);
            PendingIntent stop = PendingIntent.getService(this, 71, action,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            builder.addAction(android.R.drawable.ic_media_pause, "녹화 종료", stop);
        }
        return builder.build();
    }
    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) return START_NOT_STICKY;
        if (STOP.equals(intent.getAction())) {
            stopRequested = true;
            if (worker == null) stopSelf();
            return START_NOT_STICKY;
        }
        if (!START.equals(intent.getAction()) || running) return START_NOT_STICKY;
        int resultCode = intent.getIntExtra(RESULT_CODE, Activity.RESULT_CANCELED);
        Intent grant;
        if (android.os.Build.VERSION.SDK_INT >= 33)
            grant = intent.getParcelableExtra(RESULT_DATA, Intent.class);
        else grant = intent.getParcelableExtra(RESULT_DATA);
        if (resultCode != Activity.RESULT_OK || grant == null) {
            stopSelf(); return START_NOT_STICKY;
        }
        NotificationManager manager = (NotificationManager)getSystemService(NOTIFICATION_SERVICE);
        if (manager == null || !manager.areNotificationsEnabled()) {
            stopSelf(); return START_NOT_STICKY;
        }
        try {
            startForeground(NOTIF, notification("녹화 준비 중", "60FPS 목표 · 소리 없음", true),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
        } catch (Exception e) {
            getSharedPreferences("rec", MODE_PRIVATE).edit().putString("last", "시작 오류: " + e).apply();
            stopSelf(); return START_NOT_STICKY;
        }
        running = true;
        stopRequested = false;
        final Intent permission = grant;
        final int code = resultCode;
        worker = new Thread(() -> record(code, permission), "REC-Encoder");
        worker.start();
        return START_NOT_STICKY;
    }

    private static int alignDown(int n, int a) { return Math.max(a, n / a * a); }
    private static final class Size { final int w, h; Size(int w, int h) { this.w=w; this.h=h; } }
    private Size pickSize(MediaCodec codec, int nativeWidth, int nativeHeight) {
        MediaCodecInfo.VideoCapabilities vc = codec.getCodecInfo()
                .getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_AVC).getVideoCapabilities();
        int aw = vc.getWidthAlignment(), ah = vc.getHeightAlignment();
        double scale = 1.0;
        for (int i=0; i<22; i++) {
            int w = alignDown((int)(nativeWidth*scale), aw);
            int h = alignDown((int)(nativeHeight*scale), ah);
            if (w >= 320 && h >= 320 && vc.areSizeAndRateSupported(w, h, 60.0))
                return new Size(w, h);
            scale *= 0.91;
        }
        throw new IllegalStateException("기기 H.264 인코더가 60FPS 화면 녹화를 지원하지 않습니다.");
    }

    private void record(int resultCode, Intent grant) {
        MediaCodec codec = null;
        MediaMuxer muxer = null;
        ParcelFileDescriptor fd = null;
        MediaProjection projection = null;
        MediaProjection.Callback callback = null;
        VirtualDisplay virtualDisplay = null;
        Surface surface = null;
        Uri file = null;
        boolean encoderStarted = false, muxerStarted = false, saved = false;
        int samples = 0;
        String error = null;
        try {
            DisplayMetrics dm = new DisplayMetrics();
            WindowManager windows = (WindowManager)getSystemService(WINDOW_SERVICE);
            if (windows == null) throw new IllegalStateException("디스플레이를 찾을 수 없습니다.");
            windows.getDefaultDisplay().getRealMetrics(dm);
            codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC);
            Size size = pickSize(codec, dm.widthPixels, dm.heightPixels);
            MediaFormat format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, size.w, size.h);
            format.setInteger(MediaFormat.KEY_COLOR_FORMAT,
                    MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
            format.setInteger(MediaFormat.KEY_FRAME_RATE, 60);
            format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2);
            int bitrate = Math.max(3_000_000, Math.min(24_000_000, size.w*size.h*60/12));
            format.setInteger(MediaFormat.KEY_BIT_RATE, bitrate);
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            surface = codec.createInputSurface();
            codec.start();
            encoderStarted = true;

            ContentValues values = new ContentValues();
            values.put(MediaStore.Video.Media.DISPLAY_NAME,
                    "REC_" + new SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(new Date()) + ".mp4");
            values.put(MediaStore.Video.Media.MIME_TYPE, "video/mp4");
            values.put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/ScreenRecordings");
            values.put(MediaStore.Video.Media.IS_PENDING, 1);
            file = getContentResolver().insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values);
            if (file == null) throw new IllegalStateException("영상 저장 위치 생성 실패");
            fd = getContentResolver().openFileDescriptor(file, "w");
            if (fd == null) throw new IllegalStateException("영상 저장 파일 열기 실패");
            muxer = new MediaMuxer(fd.getFileDescriptor(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);

            MediaProjectionManager manager = (MediaProjectionManager)getSystemService(MEDIA_PROJECTION_SERVICE);
            if (manager == null) throw new IllegalStateException("화면 캡처 서비스를 찾을 수 없습니다.");
            projection = manager.getMediaProjection(resultCode, grant);
            if (projection == null) throw new IllegalStateException("화면 녹화 권한이 없습니다.");
            callback = new MediaProjection.Callback() {
                @Override public void onStop() { stopRequested = true; }
            };
            projection.registerCallback(callback, new Handler(Looper.getMainLooper()));
            virtualDisplay = projection.createVirtualDisplay(
                    "REC Screen Recorder", size.w, size.h, dm.densityDpi,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                    surface, null, null);

            NotificationManager notifications = (NotificationManager)getSystemService(NOTIFICATION_SERVICE);
            if (notifications != null) notifications.notify(NOTIF,
                    notification("화면 녹화 중", size.w+"×"+size.h+" · 60FPS 목표 · 무음", true));

            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            boolean endSent = false, endReached = false;
            long endStart = 0;
            int track = -1;
            while (!endReached) {
                if (stopRequested && !endSent) {
                    if (virtualDisplay != null) { virtualDisplay.release(); virtualDisplay = null; }
                    codec.signalEndOfInputStream();
                    endSent = true;
                    endStart = SystemClock.elapsedRealtime();
                }
                int index = codec.dequeueOutputBuffer(info, 100_000);
                if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    if (muxerStarted) throw new IllegalStateException("비디오 형식이 중복 변경되었습니다.");
                    track = muxer.addTrack(codec.getOutputFormat());
                    muxer.start();
                    muxerStarted = true;
                } else if (index >= 0) {
                    ByteBuffer buffer = codec.getOutputBuffer(index);
                    if (buffer != null && info.size > 0 &&
                            (info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                        if (!muxerStarted) throw new IllegalStateException("MP4 트랙 미생성");
                        buffer.position(info.offset);
                        buffer.limit(info.offset + info.size);
                        muxer.writeSampleData(track, buffer, info);
                        samples++;
                    }
                    endReached = (info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
                    codec.releaseOutputBuffer(index, false);
                }
                if (endSent && SystemClock.elapsedRealtime() - endStart > 7000) {
                    throw new IllegalStateException("인코더 종료 시간 초과");
                }
            }
            saved = muxerStarted && samples > 0;
            if (!saved) error = "저장할 영상 프레임이 없습니다.";
        } catch (Throwable e) {
            error = e.getClass().getSimpleName() + ": " + e.getMessage();
        } finally {
            if (virtualDisplay != null) try { virtualDisplay.release(); } catch (Exception ignored) {}
            if (projection != null) {
                try { if (callback != null) projection.unregisterCallback(callback); } catch (Exception ignored) {}
                try { projection.stop(); } catch (Exception ignored) {}
            }
            if (encoderStarted && codec != null) try { codec.stop(); } catch (Exception ignored) {}
            if (codec != null) try { codec.release(); } catch (Exception ignored) {}
            if (surface != null) try { surface.release(); } catch (Exception ignored) {}
            if (muxer != null) {
                if (muxerStarted) {
                    try { muxer.stop(); } catch (Exception e) { saved = false; error = "MP4 종료 실패: " + e.getMessage(); }
                }
                try { muxer.release(); } catch (Exception ignored) {}
            }
            if (fd != null) try { fd.close(); } catch (Exception ignored) {}
            if (file != null) {
                if (saved) {
                    try {
                        ContentValues update = new ContentValues();
                        update.put(MediaStore.Video.Media.IS_PENDING, 0);
                        getContentResolver().update(file, update, null, null);
                    } catch (Exception e) { saved = false; error = "영상 공개 실패: " + e.getMessage(); }
                }
                if (!saved) try { getContentResolver().delete(file, null, null); } catch (Exception ignored) {}
            }
            String outcome = saved ? "영상 저장 완료: Movies/ScreenRecordings" : "녹화 실패: " + (error == null ? "알 수 없는 오류" : error);
            getSharedPreferences("rec", MODE_PRIVATE).edit().putString("last", outcome).apply();
            running = false;
            try { stopForeground(STOP_FOREGROUND_REMOVE); } catch (Exception ignored) {}
            stopSelf();
        }
    }

    @Override public void onDestroy() {
        stopRequested = true;
        super.onDestroy();
    }
}
