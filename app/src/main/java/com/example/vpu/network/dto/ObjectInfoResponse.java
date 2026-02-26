package com.example.vpu.network.dto;

import com.google.gson.annotations.SerializedName;

import java.util.List;

public class ObjectInfoResponse {

    @SerializedName("objectId")
    public int objectId;

    public String address;
    public String version;
    public List<String> images;
}
