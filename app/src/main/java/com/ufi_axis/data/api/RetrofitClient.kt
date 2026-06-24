package com.ufi_axis.data.api

import com.ufi_axis.BuildConfig
import com.ufi_axis.util.AppPreferences
import com.ufi_axis.util.DebugLog
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Retry interceptor for transient failures (IOException, 5xx).
 * Retries up to [maxRetries] times with [retryDelayMs] between attempts.
 */
class RetryInterceptor(
    private val maxRetries: Int = 2,
    private val retryDelayMs: Long = 500
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): okhttp3.Response {
        val request = chain.request()
        var lastException: IOException? = null
        for (attempt in 0..maxRetries) {
            try {
                val response = chain.proceed(request)
                // Retry on 5xx server errors
                if (response.code >= 500 && attempt < maxRetries) {
                    response.close()
                    Thread.sleep(retryDelayMs)
                    continue
                }
                return response
            } catch (e: IOException) {
                lastException = e
                if (attempt < maxRetries) {
                    Thread.sleep(retryDelayMs)
                }
            }
        }
        throw lastException ?: IOException("Request failed after ${maxRetries + 1} attempts")
    }
}

object RetrofitClient {

    @Volatile
    private var apiService: UfiAxisApi? = null

    fun getApiService(prefs: AppPreferences): UfiAxisApi {
        if (apiService == null) {
            synchronized(this) {
                if (apiService == null) {
                    apiService = createApiService(prefs)
                }
            }
        }
        return apiService!!
    }

    fun recreate(prefs: AppPreferences): UfiAxisApi {
        synchronized(this) {
            apiService = createApiService(prefs)
            return apiService!!
        }
    }

    @Suppress("DEPRECATION")
    private fun createApiService(prefs: AppPreferences): UfiAxisApi {
        val ip = prefs.serverIp.ifBlank { "127.0.0.1" }
        val baseUrl = "http://$ip:${prefs.serverPort}/"

        val authInterceptor = Interceptor { chain ->
            val original = chain.request()
            val request = original.newBuilder()
                .header("Authorization", "Bearer ${prefs.token}")
                .header("Content-Type", "application/json")
                .build()
            chain.proceed(request)
        }

        val traceInterceptor = Interceptor { chain ->
            val request = chain.request()
            val response = chain.proceed(request)
            if (prefs.debugMode) {
                val url = request.url.toString()
                val body = response.peekBody(8192).string()
                DebugLog.json("HTTP", url, body)
            }
            response
        }

        val builder = OkHttpClient.Builder()
            .addInterceptor(RetryInterceptor(maxRetries = 2, retryDelayMs = 500))
            .addInterceptor(authInterceptor)
            .addInterceptor(traceInterceptor)
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(25, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)

        if (BuildConfig.DEBUG) {
            val loggingInterceptor = HttpLoggingInterceptor().apply {
                level = HttpLoggingInterceptor.Level.BODY
            }
            builder.addInterceptor(loggingInterceptor)
        }

        val client = builder.build()

        // Gson 严格模式无法处理 goform 透传的字符串数字（如 "rsrp":"99"）
        val gson = com.google.gson.GsonBuilder()
            .setLenient()
            .create()

        return Retrofit.Builder()
            .baseUrl(baseUrl)
            .client(client)
            .addConverterFactory(GsonConverterFactory.create(gson))
            .build()
            .create(UfiAxisApi::class.java)
    }
}