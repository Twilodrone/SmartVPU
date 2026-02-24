package com.example.vpu.network.dto;

import com.google.gson.annotations.SerializedName;

import java.util.List;

public class ObjectInfoResponse {

    @SerializedName("object_id")
    public int objectId;

    public String address;

    public List<String> images;
}

