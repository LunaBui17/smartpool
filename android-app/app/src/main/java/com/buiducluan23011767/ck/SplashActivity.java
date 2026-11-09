
package com.buiducluan23011767.ck;

import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.OvershootInterpolator;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

public class SplashActivity extends AppCompatActivity {

    private final Handler handler =
            new Handler(Looper.getMainLooper());

    private View splashRoot;
    private ImageView imgLogo;
    private TextView txtTitle;
    private TextView txtSubtitle;

    private boolean openedMain = false;

    private final Runnable openMainRunnable = () -> {

        if (isFinishing() || openedMain) {
            return;
        }

        openedMain = true;

        Intent intent = new Intent(
                SplashActivity.this,
                MainActivity.class
        );

        startActivity(intent);

        overridePendingTransition(
                android.R.anim.fade_in,
                android.R.anim.fade_out
        );

        finish();
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        setContentView(R.layout.activity_splash);

        splashRoot = findViewById(R.id.splashRoot);
        imgLogo = findViewById(R.id.imgSplashLogo);
        txtTitle = findViewById(R.id.txtSplashTitle);
        txtSubtitle = findViewById(R.id.txtSplashSubtitle);

        // Ban đầu ẩn các thành phần để chạy hiệu ứng.
        splashRoot.setAlpha(0f);

        imgLogo.setAlpha(0f);
        imgLogo.setScaleX(0.5f);
        imgLogo.setScaleY(0.5f);

        txtTitle.setAlpha(0f);
        txtSubtitle.setAlpha(0f);

        // Hiệu ứng nền xuất hiện.
        splashRoot.animate()
                .alpha(1f)
                .setDuration(600)
                .setInterpolator(new DecelerateInterpolator())
                .start();

        // Logo phóng to và hiện dần.
        imgLogo.animate()
                .alpha(1f)
                .scaleX(1f)
                .scaleY(1f)
                .setDuration(900)
                .setInterpolator(new OvershootInterpolator(1.2f))
                .start();

        // Tên ứng dụng xuất hiện sau logo.
        txtTitle.animate()
                .alpha(1f)
                .setStartDelay(350)
                .setDuration(700)
                .setInterpolator(new DecelerateInterpolator())
                .start();

        txtSubtitle.animate()
                .alpha(1f)
                .setStartDelay(550)
                .setDuration(700)
                .start();

        // Sau 2 giây chuyển sang MainActivity.
        handler.postDelayed(openMainRunnable, 2200);
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacks(openMainRunnable);
        super.onDestroy();
    }
}
