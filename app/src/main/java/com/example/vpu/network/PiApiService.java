package com.example.vpu.network;

import com.example.vpu.network.dto.ActivateRequest;
import com.example.vpu.network.dto.ActivateResponse;
import com.example.vpu.network.dto.PiStatusResponse;

import retrofit2.Call;
import retrofit2.http.Body;
import retrofit2.http.GET;
import retrofit2.http.POST;

public interface PiApiService {

    @GET("api/status") Call<PiStatusResponse> getStatus();
    @POST("api/manual/on") Call<Object> manualOn();
    @POST("api/manual/off") Call<Object> manualOff();
    @POST("api/activate") Call<ActivateResponse> activate(@Body ActivateRequest body);

}