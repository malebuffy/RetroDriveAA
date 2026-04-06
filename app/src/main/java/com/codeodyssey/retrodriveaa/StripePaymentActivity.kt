package com.codeodyssey.retrodriveaa

import android.app.Activity
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.codeodyssey.retrodriveaa.R
import com.stripe.android.PaymentConfiguration
import com.stripe.android.paymentsheet.PaymentSheet
import com.stripe.android.paymentsheet.PaymentSheet.BillingDetails
import com.stripe.android.paymentsheet.PaymentSheetResult
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException

class StripePaymentActivity : AppCompatActivity() {

    private lateinit var paymentSheet: PaymentSheet
    private lateinit var emailInput: EditText
    private lateinit var payButton: Button
    private lateinit var exitPayButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_stripe_payment)

        emailInput = findViewById(R.id.inputEmail)
        payButton = findViewById(R.id.buttonPay)
        exitPayButton = findViewById(R.id.BuyNowCloseButton)

        payButton.isEnabled = true

        PaymentConfiguration.init(
            applicationContext,
            TrialModeConfig.STRIPE_PUBLISHABLE_KEY
        )
        paymentSheet = PaymentSheet(this, ::onPaymentSheetResult)

        exitPayButton.setOnClickListener {
            setResult(Activity.RESULT_CANCELED)
            finish()
        }

        payButton.setOnClickListener {
            if (isNetworkAvailable()) {
                payButton.isEnabled = false
                val email = emailInput.text.toString().takeIf { it.isNotBlank() }
                createPaymentIntent(email)
            } else {
                showAlert(
                    title = "No Internet Connection",
                    message = "You need an active network to start the payment."
                )
            }
        }
    }

    private fun showAlert(
        title: String,
        message: String,
        positiveButtonText: String = getString(android.R.string.ok),
        onPositive: (() -> Unit)? = null
    ) {
        AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton(positiveButtonText) { _, _ -> onPositive?.invoke() }
            .setCancelable(true)
            .show()
    }

    private fun isNetworkAvailable(): Boolean {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    private fun createPaymentIntent(email: String?) {
        val payload = JSONObject().apply {
            put("amount", TrialModeConfig.STRIPE_AMOUNT_CENTS)
            email?.let { put("email", it) }
        }
        val body = payload.toString()
            .toRequestBody("application/json".toMediaType())

        OkHttpClient().newCall(
            Request.Builder()
                .url(TrialModeConfig.STRIPE_BACKEND_URL)
                .post(body)
                .build()
        ).enqueue(object : okhttp3.Callback {
            override fun onFailure(call: okhttp3.Call, e: IOException) {
                runOnUiThread {
                    Toast.makeText(
                        this@StripePaymentActivity,
                        "Network error: ${e.localizedMessage}",
                        Toast.LENGTH_LONG
                    ).show()
                    payButton.isEnabled = true
                }
            }

            override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                response.use {
                    if (!it.isSuccessful) {
                        runOnUiThread {
                            Toast.makeText(
                                this@StripePaymentActivity,
                                "Server error: ${it.code}",
                                Toast.LENGTH_LONG
                            ).show()
                            payButton.isEnabled = true
                        }
                        return
                    }

                    val data = JSONObject(it.body?.string() ?: "{}")
                    val clientSecret = data.optString("clientSecret").takeIf { it.isNotBlank() }
                    if (clientSecret != null) {
                        runOnUiThread {
                            val billing = BillingDetails(email = email)
                            paymentSheet.presentWithPaymentIntent(
                                clientSecret,
                                PaymentSheet.Configuration(
                                    merchantDisplayName = TrialModeConfig.STRIPE_MERCHANT_NAME,
                                    defaultBillingDetails = billing
                                )
                            )
                        }
                    } else {
                        runOnUiThread {
                            Toast.makeText(
                                this@StripePaymentActivity,
                                "Invalid response from server",
                                Toast.LENGTH_LONG
                            ).show()
                            payButton.isEnabled = true
                        }
                    }
                }
            }
        })
    }

    private fun onPaymentSheetResult(result: PaymentSheetResult) {
        when (result) {
            is PaymentSheetResult.Completed -> {
                setResult(RESULT_OK)
                finish()
            }
            is PaymentSheetResult.Canceled -> {
                Toast.makeText(this, "Payment canceled", Toast.LENGTH_SHORT).show()
                setResult(RESULT_CANCELED)
                finish()
            }
            is PaymentSheetResult.Failed -> {
                Toast.makeText(
                    this,
                    "Payment failed: ${result.error.localizedMessage}",
                    Toast.LENGTH_LONG
                ).show()
                setResult(RESULT_CANCELED)
                finish()
            }
        }
    }
}
