package com.example.test_project

import android.content.Context
import androidx.work.WorkerParameters

/** Keeps downloads scheduled before the package refactor restorable by WorkManager. */
class ModelDownloadWorker(context: Context, parameters: WorkerParameters) :
    com.example.test_project.processing.model.ModelDownloadWorker(context, parameters)
