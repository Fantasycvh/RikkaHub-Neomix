package app.androidrik.rikkahub.data.api

import app.androidrik.rikkahub.data.model.Sponsor
import app.androidrik.rikkahub.utils.JsonInstant
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import retrofit2.http.GET

interface SponsorAPI {
    @GET("/sponsors")
    suspend fun getSponsors(): List<Sponsor>

    companion object {
        fun create(httpClient: OkHttpClient): SponsorAPI {
            return Retrofit.Builder()
                .client(httpClient)
                .baseUrl("https://sponsors.rikka-ai.com")
                .addConverterFactory(JsonInstant.asConverterFactory("application/json".toMediaType()))
                .build()
                .create(SponsorAPI::class.java)
        }
    }
}
