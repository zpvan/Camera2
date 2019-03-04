package com.knox.camera2raw;

import android.os.Bundle;
import android.support.v7.app.AppCompatActivity;
import android.view.Window;
import android.view.WindowManager;

public class MainActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // 设置全屏
        supportRequestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN,
                WindowManager.LayoutParams.FLAG_FULLSCREEN);

        // 配置布局
        setContentView(R.layout.activity_main);

        // 启用fragment
        getSupportFragmentManager().beginTransaction()
                .replace(R.id.root_fl, Camera2Fragment.newInstance())
                .commit();
    }
}
