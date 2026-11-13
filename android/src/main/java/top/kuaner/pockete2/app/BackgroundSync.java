// Copyright 2026 pocket-e2 contributors
// SPDX-License-Identifier: FSL-1.1-Apache-2.0

package top.kuaner.pockete2.app;

import android.app.job.*;
import android.content.*;

import top.kuaner.pockete2.core.PocketController;
import top.kuaner.pockete2.core.Settings;

/** Android periodic catch-up. APNs is iOS-specific; Android scheduling may be deferred by Doze. */
public final class BackgroundSync extends JobService {
    private static final int JOB = 20261114;

    public static void schedule(Context context) {
        Settings settings = new Settings(context);
        JobScheduler jobs = context.getSystemService(JobScheduler.class);
        if (!settings.isComplete()) {
            jobs.cancel(JOB);
            return;
        }
        jobs.schedule(
                new JobInfo.Builder(JOB, new ComponentName(context, BackgroundSync.class))
                        .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                        .setPeriodic(15 * 60_000)
                        .setPersisted(true)
                        .build());
    }

    @Override
    public boolean onStartJob(JobParameters params) {
        PocketController.get(this).backgroundFetch(() -> jobFinished(params, false));
        return true;
    }

    @Override
    public boolean onStopJob(JobParameters params) {
        return true;
    }
}
