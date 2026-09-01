package com.example.babymonitor;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.view.View;
import android.view.WindowManager;
import android.widget.Toast;

import com.google.mlkit.vision.codescanner.GmsBarcodeScanner;
import com.google.zxing.ResultPoint;
import com.journeyapps.barcodescanner.BarcodeCallback;
import com.journeyapps.barcodescanner.BarcodeResult;
import com.journeyapps.barcodescanner.DecoratedBarcodeView;

import java.util.List;

public class ArgusQrScannerActivity extends Activity {
    private static final int REQ_CAMERA = 501;

    private DecoratedBarcodeView barcodeView;
    private boolean scannerStarted = false;
    private boolean finished = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        HebrewLocale.apply(this);
        getWindow().getDecorView().setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        barcodeView = new DecoratedBarcodeView(this);
        barcodeView.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        barcodeView.setStatusText("סרקו את קוד ה QR שמופיע בטלפון הילד");
        setContentView(barcodeView);

        if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startScanner();
        } else {
            requestPermissions(new String[]{Manifest.permission.CAMERA}, REQ_CAMERA);
        }
    }

    private void startScanner() {
        if (scannerStarted || finished) return;
        scannerStarted = true;

        barcodeView.decodeContinuous(new BarcodeCallback() {
            @Override
            public void barcodeResult(BarcodeResult result) {
                if (finished || result == null) return;
                String text = result.getText();
                if (text == null || text.trim().isEmpty()) return;

                finished = true;
                barcodeView.pause();
                GmsBarcodeScanner.deliverSuccess(text);
                finish();
            }

            @Override
            public void possibleResultPoints(List<ResultPoint> resultPoints) {
            }
        });

        barcodeView.resume();
    }

    @Override
    protected void onResume() {
        super.onResume();
        HebrewLocale.apply(this);
        if (scannerStarted && !finished) barcodeView.resume();
    }

    @Override
    protected void onPause() {
        if (barcodeView != null) barcodeView.pause();
        super.onPause();
    }

    @Override
    public void onBackPressed() {
        if (!finished) {
            finished = true;
            GmsBarcodeScanner.deliverCanceled();
        }
        super.onBackPressed();
    }

    @Override
    public void onRequestPermissionsResult(
            int requestCode,
            String[] permissions,
            int[] grantResults
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);

        if (requestCode != REQ_CAMERA) return;

        if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            startScanner();
        } else {
            finished = true;
            GmsBarcodeScanner.deliverFailure(
                    new SecurityException("הרשאת המצלמה לא אושרה")
            );
            Toast.makeText(this, "נדרשת הרשאת מצלמה כדי לסרוק את קוד ה QR", Toast.LENGTH_LONG).show();
            finish();
        }
    }
}
