import android.os.Bundle;
import android.os.CountDownTimer;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.bumptech.glide.Glide;

import org.json.JSONObject;

import java.io.IOException;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

public class VendingPaymentActivity extends AppCompatActivity {

    private static final String API_KEY = "YOUR_DRIPPAY_API_KEY";
    private static final String BASE_URL = "https://api.drippay-dev.com/api/v1/invoices";
    
    private TextView tvStatus, tvTimer;
    private Button btnPayCrypto, btnCancel;
    private ImageView ivQrCode;

    private OkHttpClient client;
    private CountDownTimer countDownTimer;
    private Handler pollHandler;
    private Runnable pollRunnable;
    
    private String currentPaymentReference = null;
    private boolean isPaymentActive = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_vending_payment);

        tvStatus = findViewById(R.id.tvStatus);
        tvTimer = findViewById(R.id.tvTimer);
        btnPayCrypto = findViewById(R.id.btnPayCrypto);
        btnCancel = findViewById(R.id.btnCancel);
        ivQrCode = findViewById(R.id.ivQrCode);

        client = new OkHttpClient();
        pollHandler = new Handler(Looper.getMainLooper());

        btnPayCrypto.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                createPaymentRequest();
            }
        });

        btnCancel.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                cancelPayment();
            }
        });
    }

    // 1. CREATE PAYMENT REQUEST
    private void createPaymentRequest() {
        btnPayCrypto.setEnabled(false);
        tvStatus.setText("Generating QR Code...");

        MediaType JSON = MediaType.parse("application/json; charset=utf-8");
        // Mock payload for the vending machine item
        String jsonBody = "{"
                + "\"machine_id\": \"VEND-001\","
                + "\"trade_no\": \"ORDER-" + System.currentTimeMillis() + "\","
                + "\"currency\": \"USD\","
                + "\"items\":[{\"description\": \"Soda\",\"amount\": 2.50}]"
                + "}";

        RequestBody body = RequestBody.create(JSON, jsonBody);
        Request request = new Request.Builder()
                .url(BASE_URL)
                .addHeader("Authorization", "Bearer " + API_KEY)
                .post(body)
                .build();

        client.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                runOnUiThread(() -> {
                    tvStatus.setText("Network error. Try again.");
                    btnPayCrypto.setEnabled(true);
                });
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                if (response.isSuccessful() && response.body() != null) {
                    try {
                        String responseData = response.body().string();
                        JSONObject jsonObject = new JSONObject(responseData);
                        
                        // Extract required fields from API response
                        currentPaymentReference = jsonObject.getString("reference");
                        String qrUrl = jsonObject.getString("qr_code_image_url");

                        runOnUiThread(() -> startPaymentFlow(qrUrl));

                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                }
            }
        });
    }

    // 2. SHOW QR CODE & START TIMERS
    private void startPaymentFlow(String qrUrl) {
        isPaymentActive = true;
        btnPayCrypto.setVisibility(View.GONE);
        ivQrCode.setVisibility(View.VISIBLE);
        tvTimer.setVisibility(View.VISIBLE);
        btnCancel.setVisibility(View.VISIBLE);
        tvStatus.setText("Please scan to pay");

        // Load QR image via Glide
        Glide.with(this).load(qrUrl).into(ivQrCode);

        // Start 60-second countdown
        countDownTimer = new CountDownTimer(60000, 1000) {
            public void onTick(long millisUntilFinished) {
                tvTimer.setText("Time remaining: " + millisUntilFinished / 1000 + "s");
            }

            public void onFinish() {
                if (isPaymentActive) {
                    tvTimer.setText("Timeout!");
                    cancelPayment();
                }
            }
        }.start();

        // Start polling every 3 seconds
        pollRunnable = new Runnable() {
            @Override
            public void run() {
                if (isPaymentActive && currentPaymentReference != null) {
                    checkPaymentStatus();
                    // Re-run this block in 3 seconds
                    pollHandler.postDelayed(this, 3000); 
                }
            }
        };
        pollHandler.postDelayed(pollRunnable, 3000);
    }

    // 3. POLL PAYMENT STATUS
    private void checkPaymentStatus() {
        Request request = new Request.Builder()
                .url(BASE_URL + "/" + currentPaymentReference + "/status")
                .addHeader("Authorization", "Bearer " + API_KEY)
                .get()
                .build();

        client.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                // Ignore failure, we will try again in 3 seconds
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                if (response.isSuccessful() && response.body() != null) {
                    try {
                        JSONObject jsonObject = new JSONObject(response.body().string());
                        String status = jsonObject.getString("status");

                        if ("paid".equalsIgnoreCase(status)) {
                            runOnUiThread(() -> handlePaymentSuccess());
                        }
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                }
            }
        });
    }

    // 4. DISPENSE PRODUCT
    private void handlePaymentSuccess() {
        stopTimers();
        ivQrCode.setVisibility(View.GONE);
        tvTimer.setVisibility(View.GONE);
        btnCancel.setVisibility(View.GONE);
        
        tvStatus.setText("Payment Successful! Dispensing item...");
        
        // TODO: Call vending machine hardware SDK to dispense item here
        
        // Reset UI after 3 seconds
        new Handler(Looper.getMainLooper()).postDelayed(this::resetUI, 3000);
    }

    // 5. HANDLE CANCELLATION & TIMEOUT
    private void cancelPayment() {
        stopTimers();
        tvStatus.setText("Cancelling order...");

        if (currentPaymentReference != null) {
            Request request = new Request.Builder()
                    .url(BASE_URL + "/" + currentPaymentReference)
                    .addHeader("Authorization", "Bearer " + API_KEY)
                    .delete()
                    .build();

            client.newCall(request).enqueue(new Callback() {
                @Override
                public void onFailure(Call call, IOException e) {
                    runOnUiThread(() -> resetUI());
                }

                @Override
                public void onResponse(Call call, Response response) throws IOException {
                    runOnUiThread(() -> resetUI());
                }
            });
        } else {
            resetUI();
        }
    }

    // 6. UTILITIES
    private void stopTimers() {
        isPaymentActive = false;
        if (countDownTimer != null) countDownTimer.cancel();
        if (pollHandler != null && pollRunnable != null) pollHandler.removeCallbacks(pollRunnable);
    }

    private void resetUI() {
        stopTimers();
        currentPaymentReference = null;
        tvStatus.setText("Select payment method");
        btnPayCrypto.setVisibility(View.VISIBLE);
        btnPayCrypto.setEnabled(true);
        ivQrCode.setVisibility(View.GONE);
        tvTimer.setVisibility(View.GONE);
        btnCancel.setVisibility(View.GONE);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        stopTimers(); // Prevent memory leaks if the user closes the app mid-payment
    }
}
