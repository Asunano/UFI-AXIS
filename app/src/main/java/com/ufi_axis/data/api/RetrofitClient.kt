package com.ufi_axis.data.api

import com.ufi_axis.BuildConfig
import com.ufi_axis.util.AppPreferences
import com.ufi_axis.util.DebugLog
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

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
            .addInterceptor(authInterceptor)
            .addInterceptor(traceInterceptor)
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(25, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)

        if (BuildConfig.DEBUG) {
            val loggingInterceptor = HttpLoggingInterceptor().apply {
                level = HttpLoggingInterceptor.Level.BODY
            }
            builder.addInterceptor(loggingInterceptor)
        }

        val client = builder.build()

        val gson = com.google.gson.Gson()

        return Retrofit.Builder()
            .baseUrl(baseUrl)
            .client(client)
            .addConverterFactory(GsonConverterFactory.create(gson))
            .build()
            .create(UfiAxisApi::class.java)
    }
}