package com.scheduler.jobengine.model;

public enum JobStatus {
    PENDING,   //submitted. waiting to be picked up
    RUNNING,   //executing currently in the thread
    RETRYING,  //failed currently. backs of for retrying later
    COMPLETED, //finished successfully
    DEAD       //max retries exceeded. failed permanently
}
