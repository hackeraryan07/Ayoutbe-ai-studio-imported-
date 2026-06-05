package com.example.myapp.model;

import java.io.Serializable;

public class StreamOption implements Serializable {
    private String resolution;
    private String url;

    public StreamOption(String resolution, String url) {
        this.resolution = resolution;
        this.url = url;
    }

    public String getResolution() { return resolution; }
    public String getUrl() { return url; }

    @Override
    public String toString() {
        return resolution;
    }
}
