package com.example.getconnected

import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.GET
import retrofit2.http.Query

// Data class to represent the overall API response


// Data class to represent each job returned by Jobicy


interface JobicyApiService {
    @GET("remote-jobs")
    suspend fun getRemoteJobs(
        @Query("count") count: Int = 20,
        @Query("geo") geo: String? = null,
        @Query("industry") industry: String? = null,
        @Query("tag") tag: String? = null
    ): JobicyResponse

    companion object {
        private const val BASE_URL = "https://jobicy.com/api/v2/"

        fun create(): JobicyApiService {
            return Retrofit.Builder()
                .baseUrl(BASE_URL)
                .addConverterFactory(GsonConverterFactory.create())
                .build()
                .create(JobicyApiService::class.java)
        }
    }
}