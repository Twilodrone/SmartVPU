package com.example.vpu.network;

import com.example.vpu.network.dto.ObjectInfoResponse;
import com.example.vpu.network.dto.TrafficRequest;

import retrofit2.Call;
import retrofit2.http.Body;
import retrofit2.http.POST;

public interface ApiService {

    @POST("api/traffic-light")
    Call<ObjectInfoResponse> getObjectInfo(@Body TrafficRequest request);

    Call<Void> activatePhase(int objectId, int phaseNumber);
}



