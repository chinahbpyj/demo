package com.mirror.demo;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.util.DisplayMetrics;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import com.tvos.androidmirror.AirPlayClientCallback;
import com.tvos.androidmirror.AirplayClientInterface;
import com.tvos.androidmirror.AirplayUtils;
import com.tvos.androidmirror.CaptureService;
import com.tvos.androidmirror.util.LOG;

import java.io.File;
import java.util.ArrayList;
import java.util.Map;

import javax.jmdns.ServiceInfo;

public class MainActivity extends AppCompatActivity implements AirPlayClientCallback {
    private static final int REQUEST_MEDIA_PROJECTION = 1;
    private static final int REQUEST_CODE_AI_AUDIO = 101;
    private static final int REQUEST_CODE_STORAGE = 102;
    private MediaProjectionManager mMediaProjectionManager;
    private int mScreenDensity;
    private int mResultCode;
    private Intent mResultData;
    private boolean isMirrorConnected = false;
    private AirplayClientInterface mAirplayClientService;
    private ArrayList<ServiceInfo> mListDataSet;
    private final Handler mUIHandler = new Handler();
    private ServiceInfo mServiceInfo;
    private BaseAdapter mListAdapter;
    private Button button;
    private TextView text;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        text = findViewById(R.id.text);
        button = findViewById(R.id.button);
        button.setOnClickListener(view -> {
            if (mServiceInfo == null) {
                Toast.makeText(MainActivity.this, "请选择一个设备", Toast.LENGTH_SHORT).show();
                return;
            }

            if (!isMirrorConnected) {
                if (mResultCode == 0 || mResultData == null) {

                    if (Build.VERSION.SDK_INT >= 29 && !(ContextCompat.checkSelfPermission(MainActivity.this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)) {
                        requestPermissionWithCode(MainActivity.this, Manifest.permission.RECORD_AUDIO, REQUEST_CODE_AI_AUDIO);
                    } else {
                        if (Build.VERSION.SDK_INT >= 29) {//8.0后才支持
                            Intent intent = new Intent(MainActivity.this, CaptureService.class);
                            startForegroundService(intent);
                        }

                        mMediaProjectionManager = (MediaProjectionManager)
                                getSystemService(Context.MEDIA_PROJECTION_SERVICE);

                        startActivityForResult(
                                mMediaProjectionManager.createScreenCaptureIntent(),
                                REQUEST_MEDIA_PROJECTION);
                    }
                } else {
                    setMirrorType();
                }
            } else {
                isMirrorConnected = false;
                mAirplayClientService.StopMirror();
            }
        });

        mAirplayClientService = AirplayClientInterface.getInstance();
        mAirplayClientService.RegisterCallback(this);
        Thread thread = new Thread() {
            @Override
            public void run() {
                mAirplayClientService.getAirplyDiscover().setLocalAddress(AirplayUtils.getWifiInetAddress());
                mAirplayClientService.getAirplyDiscover().startDiscovery();
            }
        };
        thread.start();

        mListAdapter = new BaseAdapter() {

            @Override
            public int getCount() {
                int count = 0;
                if (mListDataSet != null)
                    count = mListDataSet.size();
                return count;
            }

            @Override
            public ServiceInfo getItem(int i) {
                if (mListDataSet == null)
                    return null;
                return mListDataSet.get(i);
            }

            @Override
            public long getItemId(int i) {
                return 0;
            }

            @Override
            public View getView(int i, View view, ViewGroup viewGroup) {
                if (view == null) {
                    view = getLayoutInflater().inflate(android.R.layout.simple_list_item_single_choice, viewGroup, false);
                }

                TextView tv = view.findViewById(android.R.id.text1);
                ServiceInfo info = getItem(i);
                if (info != null) {
                    tv.setText(info.getName());
                    tv.setTag(info);
                }
                return view;
            }
        };

        ListView mList = findViewById(R.id.listView);
        mList.setAdapter(mListAdapter);
        mList.setChoiceMode(ListView.CHOICE_MODE_SINGLE);
        mList.setOnItemClickListener((adapterView, view, i, l) -> {
            mServiceInfo = (ServiceInfo) view.getTag();
            mAirplayClientService.setServiceInfo(mServiceInfo);
        });

        DisplayMetrics metrics = new DisplayMetrics();
        getWindowManager().getDefaultDisplay().getMetrics(metrics);
        mScreenDensity = metrics.densityDpi;
    }

    private boolean requestPermissionWithCode(Activity activity, String permission, int requestCode) {
        int permissionCode = ActivityCompat.checkSelfPermission(activity, permission);

        if (permissionCode != PackageManager.PERMISSION_GRANTED) {
            // Android M Permission check
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                activity.requestPermissions(new String[]{permission}, requestCode);
            } else {
                ActivityCompat.requestPermissions(activity, new String[]{permission}, requestCode);
            }

            return false;
        }

        return true;
    }

    private void setMirrorType() {
        if (!(ContextCompat.checkSelfPermission(MainActivity.this, Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED)) {
            requestPermissionWithCode(MainActivity.this, Manifest.permission.WRITE_EXTERNAL_STORAGE, REQUEST_CODE_STORAGE);
        } else {
            startMirror();
        }
    }

    private void startMirror() {
        Thread thread = new Thread() {
            @Override
            public void run() {
                mAirplayClientService.StartMirror(MainActivity.this, mScreenDensity, mResultCode, mResultData, null, mAirplayClientService.getServiceInfo().getHostAddress());
            }
        };
        thread.start();
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_MEDIA_PROJECTION) {
            if (resultCode != Activity.RESULT_OK) {
                Toast.makeText(this, "取消", Toast.LENGTH_SHORT).show();
                finish();
                return;
            }

            mResultCode = resultCode;
            mResultData = data;
            setMirrorType();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        if (requestCode == REQUEST_CODE_STORAGE) {
            boolean grant = grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED;
            if (grant) {
                startMirror();
            }
        } else if (Build.VERSION.SDK_INT >= 29 && REQUEST_CODE_AI_AUDIO == requestCode) {
            boolean grant = grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED;
            if (grant) {
                Intent intent = new Intent(this, CaptureService.class);
                startForegroundService(intent);

                MediaProjectionManager mediaProjectionManager = (MediaProjectionManager) getApplicationContext().getSystemService(MEDIA_PROJECTION_SERVICE);
                startActivityForResult(mediaProjectionManager.createScreenCaptureIntent(), REQUEST_MEDIA_PROJECTION);
            }
        }
    }

    @Override
    public void onAirplayServiceResolved(Map<String, ServiceInfo> map) {
        mListDataSet = new ArrayList<>(map.values());
        mUIHandler.post(() -> mListAdapter.notifyDataSetChanged());
    }

    @Override
    public void onRequireMirrorSuccess() {
        mUIHandler.post(() -> {
            File picturesDir = getExternalFilesDir(null);
            String path = picturesDir.getAbsolutePath() + "/" + System.currentTimeMillis() + "_aac.dat";
            LOG.setPath(path);
            text.setText("音频存储路径:" + path);

            button.setText("stop");
            isMirrorConnected = true;
            Toast.makeText(getApplicationContext(), "Mirror to " + mServiceInfo.getName(), Toast.LENGTH_SHORT).show();
        });
    }

    @Override
    public void onRequireMirrorFailed() {
        if (Build.VERSION.SDK_INT >= 29) {
            Intent intent = new Intent(this, CaptureService.class);
            stopService(intent);
        }
    }

    @Override
    public void onStopMirrorCompleted() {
        mUIHandler.post(() -> {
            button.setText("start");
            isMirrorConnected = false;
            Toast.makeText(getApplicationContext(), "Mirror end", Toast.LENGTH_SHORT).show();
        });
    }

    @Override
    public void onMirrorDisconnected() {
    }
}