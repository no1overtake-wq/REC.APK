package kr.screenrecorder.simple;

import android.Manifest;
import android.app.Activity;
import android.app.NotificationManager;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

public final class MainActivity extends Activity {
    private static final int REQUEST_NOTIFICATION = 501;
    private static final int REQUEST_PROJECTION = 502;
    private TextView state;
    private Button start;
    private boolean permissionRequestActive;

    @Override public void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().setStatusBarColor(Color.rgb(17, 24, 39));
        getWindow().setNavigationBarColor(Color.rgb(17, 24, 39));
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(dp(22), dp(50), dp(22), dp(20));
        layout.setGravity(Gravity.CENTER_HORIZONTAL);
        layout.setBackgroundColor(Color.rgb(20, 25, 36));

        TextView title = new TextView(this);
        title.setText("REC  화면 녹화");
        title.setTextSize(26);
        title.setTextColor(Color.WHITE);
        title.setGravity(Gravity.CENTER);
        layout.addView(title, new LinearLayout.LayoutParams(-1, dp(90)));

        state = new TextView(this);
        state.setTextSize(16);
        state.setTextColor(Color.rgb(200, 210, 226));
        state.setGravity(Gravity.CENTER);
        layout.addView(state, new LinearLayout.LayoutParams(-1, dp(110)));

        start = new Button(this);
        start.setAllCaps(false);
        start.setText("녹화 시작");
        start.setTextSize(18);
        start.setOnClickListener(v -> checkAndStart());
        layout.addView(start, new LinearLayout.LayoutParams(-1, dp(62)));

        TextView help = new TextView(this);
        help.setText("60FPS 목표  ·  소리 없음\n알림창에서 ‘녹화 종료’를 누르면 MP4로 저장됩니다.\n저장 위치: 동영상 / ScreenRecordings");
        help.setTextSize(13);
        help.setTextColor(Color.rgb(150, 165, 190));
        help.setGravity(Gravity.CENTER);
        help.setLineSpacing(dp(4), 1);
        layout.addView(help, new LinearLayout.LayoutParams(-1, dp(125)));
        setContentView(layout);
        refresh();
    }

    private int dp(int n) { return Math.round(n * getResources().getDisplayMetrics().density); }
    private boolean notificationAllowed() {
        if (Build.VERSION.SDK_INT >= 33 &&
                checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            return false;
        NotificationManager manager = (NotificationManager)getSystemService(NOTIFICATION_SERVICE);
        return manager != null && manager.areNotificationsEnabled();
    }
    private void checkAndStart() {
        if (RecordingService.running) {
            refresh();
            return;
        }
        if (!notificationAllowed()) {
            if (Build.VERSION.SDK_INT >= 33 &&
                    checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                permissionRequestActive = true;
                requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQUEST_NOTIFICATION);
            } else {
                state.setText("알림이 꺼져 있습니다. 설정에서 이 앱의 알림을 허용해 주세요.");
                Intent intent = new Intent("android.settings.APP_NOTIFICATION_SETTINGS");
                intent.putExtra("android.provider.extra.APP_PACKAGE", getPackageName());
                startActivity(intent);
            }
            return;
        }
        MediaProjectionManager mgr =
                (MediaProjectionManager)getSystemService(MEDIA_PROJECTION_SERVICE);
        if (mgr == null) {
            state.setText("이 휴대폰에서는 화면 캡처 기능을 사용할 수 없습니다.");
            return;
        }
        startActivityForResult(mgr.createScreenCaptureIntent(), REQUEST_PROJECTION);
    }
    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grants) {
        super.onRequestPermissionsResult(requestCode, permissions, grants);
        if (requestCode == REQUEST_NOTIFICATION && permissionRequestActive) {
            permissionRequestActive = false;
            if (notificationAllowed()) checkAndStart();
            else state.setText("녹화를 종료할 알림을 표시해야 하므로 알림 권한이 필요합니다.");
        }
    }
    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_PROJECTION) return;
        if (resultCode != RESULT_OK || data == null) {
            state.setText("사용자가 화면 녹화 권한을 취소했습니다.");
            return;
        }
        Intent cmd = new Intent(this, RecordingService.class);
        cmd.setAction(RecordingService.START);
        cmd.putExtra(RecordingService.RESULT_CODE, resultCode);
        cmd.putExtra(RecordingService.RESULT_DATA, data);
        try {
            startForegroundService(cmd);
            state.setText("녹화 준비 중입니다. 알림창에서 종료할 수 있습니다.");
            start.setEnabled(false);
        } catch (Exception e) {
            state.setText("녹화를 시작할 수 없습니다: " + e.getClass().getSimpleName());
        }
    }
    @Override protected void onResume() { super.onResume(); refresh(); }
    private void refresh() {
        if (state == null) return;
        if (RecordingService.running) {
            state.setText("화면 녹화 중\n알림창에서 녹화 종료");
            start.setEnabled(false);
        } else {
            state.setText("녹화 준비\n화면 공유·녹화 권한을 허용하면 시작합니다.");
            start.setEnabled(true);
        }
    }
}
