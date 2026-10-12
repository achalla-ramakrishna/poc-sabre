package com.rewardsiq.publish.batch;

/** Where snapshot files go: a local directory now, a Cloud Storage bucket in the cloud. */
public interface FileSink {

    void write(String fileName, byte[] content);
}
