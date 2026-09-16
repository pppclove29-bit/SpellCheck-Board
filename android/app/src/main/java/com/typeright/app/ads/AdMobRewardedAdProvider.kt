package com.typeright.app.ads

import android.app.Activity
import android.content.Context
import android.util.Log
import com.google.android.gms.ads.AdError
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.FullScreenContentCallback
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.MobileAds
import com.google.android.gms.ads.OnUserEarnedRewardListener
import com.google.android.gms.ads.rewarded.RewardedAd
import com.google.android.gms.ads.rewarded.RewardedAdLoadCallback
import com.google.android.gms.ads.rewarded.ServerSideVerificationOptions
import com.typeright.app.BuildConfig
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

private const val TAG = "TypeRightAds"

/**
 * AdMob rewarded ads, host app only (never inside the IME).
 *
 * The client never credits itself: [ServerSideVerificationOptions.setUserId] carries the Supabase user id, AdMob
 * calls `/v1/ads/admob-ssv`, and the backend verifies the signature before adding quota. "Ad watched" claimed by a
 * patched client buys nothing.
 */
class AdMobRewardedAdProvider(context: Context) : RewardedAdProvider {

    private val app = context.applicationContext
    private val initMutex = Mutex()
    private var initialized = false

    override val isAvailable: Boolean = true

    override suspend fun showRewardedAd(activity: Activity, userId: String): RewardedAdResult {
        initialize()
        val ad = load(userId) ?: return RewardedAdResult.NotAvailable
        return show(activity, ad)
    }

    private suspend fun initialize() = initMutex.withLock {
        if (initialized) return@withLock
        // Blocking work on the first call (disk + network); MobileAds itself posts the callback on the main thread.
        withContext(Dispatchers.IO) { MobileAds.initialize(app) {} }
        initialized = true
    }

    private suspend fun load(userId: String): RewardedAd? {
        val loaded = CompletableDeferred<RewardedAd?>()
        withContext(Dispatchers.Main) {
            RewardedAd.load(
                app,
                BuildConfig.ADMOB_REWARDED_UNIT_ID,
                AdRequest.Builder().build(),
                object : RewardedAdLoadCallback() {
                    override fun onAdLoaded(ad: RewardedAd) {
                        // The server credits whoever this id names, so it must be the Supabase user id.
                        ad.setServerSideVerificationOptions(
                            ServerSideVerificationOptions.Builder().setUserId(userId).build(),
                        )
                        loaded.complete(ad)
                    }

                    override fun onAdFailedToLoad(error: LoadAdError) {
                        Log.w(TAG, "rewarded ad failed to load: ${error.code} ${error.message}")
                        loaded.complete(null)
                    }
                },
            )
        }
        return loaded.await()
    }

    private suspend fun show(activity: Activity, ad: RewardedAd): RewardedAdResult {
        val outcome = CompletableDeferred<RewardedAdResult>()
        var earned = false
        withContext(Dispatchers.Main) {
            ad.fullScreenContentCallback = object : FullScreenContentCallback() {
                override fun onAdDismissedFullScreenContent() {
                    // Dismissing after earning still counts: AdMob has already fired the SSV callback.
                    if (!outcome.isCompleted) {
                        outcome.complete(if (earned) RewardedAdResult.Rewarded else RewardedAdResult.Dismissed)
                    }
                }

                override fun onAdFailedToShowFullScreenContent(error: AdError) {
                    if (!outcome.isCompleted) outcome.complete(RewardedAdResult.Failed(error.message))
                }
            }
            ad.show(activity, OnUserEarnedRewardListener { earned = true })
        }
        return outcome.await()
    }
}
