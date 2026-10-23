package com.deportes.channel;

import android.app.job.JobParameters;
import android.app.job.JobService;

public class RefreshJobService extends JobService {
    @Override
    public boolean onStartJob(JobParameters params) {
        ChannelManager.publishAsync(this);
        jobFinished(params, false);
        return true;
    }

    @Override
    public boolean onStopJob(JobParameters params) {
        return false;
    }
}
