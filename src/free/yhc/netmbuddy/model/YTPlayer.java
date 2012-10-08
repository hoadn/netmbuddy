/*****************************************************************************
 *    Copyright (C) 2012 Younghyung Cho. <yhcting77@gmail.com>
 *
 *    This file is part of YTMPlayer.
 *
 *    This program is free software: you can redistribute it and/or modify
 *    it under the terms of the GNU Lesser General Public License as
 *    published by the Free Software Foundation either version 3 of the
 *    License, or (at your option) any later version.
 *
 *    This program is distributed in the hope that it will be useful,
 *    but WITHOUT ANY WARRANTY; without even the implied warranty of
 *    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *    GNU Lesser General Public License
 *    (<http://www.gnu.org/licenses/lgpl.html>) for more details.
 *
 *    You should have received a copy of the GNU General Public License
 *    along with this program.  If not, see <http://www.gnu.org/licenses/>.
 *****************************************************************************/

package free.yhc.netmbuddy.model;

import static free.yhc.netmbuddy.model.Utils.eAssert;
import static free.yhc.netmbuddy.model.Utils.logD;
import static free.yhc.netmbuddy.model.Utils.logI;
import static free.yhc.netmbuddy.model.Utils.logW;

import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Random;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.Uri;
import android.net.wifi.WifiManager;
import android.net.wifi.WifiManager.WifiLock;
import android.os.PowerManager;
import android.os.PowerManager.WakeLock;
import android.telephony.TelephonyManager;
import android.view.ViewGroup;
import free.yhc.netmbuddy.model.YTDownloader.DnArg;
import free.yhc.netmbuddy.model.YTDownloader.DownloadDoneReceiver;

public class YTPlayer implements
MediaPlayer.OnBufferingUpdateListener,
MediaPlayer.OnCompletionListener,
MediaPlayer.OnPreparedListener,
MediaPlayer.OnErrorListener,
MediaPlayer.OnInfoListener,
MediaPlayer.OnVideoSizeChangedListener,
MediaPlayer.OnSeekCompleteListener {
    private static final String WLTAG               = "YTPlayer";
    private static final int    PLAYER_ERR_RETRY    = Policy.YTPLAYER_RETRY_ON_ERROR;

    private static final Comparator<NrElem> sNrElemComparator = new Comparator<NrElem>() {
        @Override
        public int
        compare(NrElem o1, NrElem o2) {
            if (o1.n > o2.n)
                return 1;
            else if (o1.n < o2.n)
                return -1;
            else
                return 0;
        }
    };

    private static final Comparator<Video> sVideoTitleComparator = new Comparator<Video>() {
        @Override
        public int
        compare(Video o1, Video o2) {
            return o1.title.compareTo(o2.title);
        }
    };

    private static File     sCacheDir = new File(Policy.APPDATA_CACHEDIR);

    private static YTPlayer sInstance = null;

    private final DB            mDb         = DB.get();

    // ------------------------------------------------------------------------
    //
    // ------------------------------------------------------------------------
    private final YTPlayerUI            mUi         = new YTPlayerUI(this); // for UI control
    private final AutoStop              mAutoStop   = new AutoStop();
    private final StartVideoRecovery    mStartVideoRecovery = new StartVideoRecovery();
    private final VideoListManager      mVlm;

    // ------------------------------------------------------------------------
    //
    // ------------------------------------------------------------------------

    private WakeLock            mWl         = null;
    private WifiLock            mWfl        = null;
    private MediaPlayer         mMp         = null;
    private MPState             mMpS        = MPState.INVALID; // state of mMp;
    private int                 mMpVol      = Policy.DEFAULT_VIDEO_VOLUME; // Current volume of media player.
    private YTHacker            mYtHack     = null;
    private NetLoader           mLoader     = null;
    // assign dummy instance to remove "if (null != mYtDnr)"
    private YTDownloader        mYtDnr      = new YTDownloader();

    // ------------------------------------------------------------------------
    // Runtime Status
    // ------------------------------------------------------------------------
    private int                     mErrRetry = PLAYER_ERR_RETRY;
    private YTPState                mYtpS   = YTPState.IDLE;
    private int                     mLastBuffering  = -1;

    // ------------------------------------------------------------------------
    // Listeners
    // ------------------------------------------------------------------------
    private VideosStateListener     mVStateLsnr = null;
    private PlayerStateListener     mPStateLsnr = null;

    public interface VideosStateListener {
        /**
         *  playing videos in the queue is started
         */
        void onStarted();
        /**
         * playing videos in the queue is stopped
         */
        void onStopped(StopState state);
        /**
         * video-queue of the player is changed.
         */
        void onChanged();
    }

    public interface PlayerStateListener {
        void onStateChanged(MPState from, MPState to);
        void onBufferingChanged(int percent);
    }


    // see "http://developer.android.com/reference/android/media/MediaPlayer.html"
    public static enum MPState {
        INVALID,
        IDLE,
        INITIALIZED,
        PREPARING,
        PREPARED,
        STARTED,
        STOPPED,
        PAUSED,
        PLAYBACK_COMPLETED,
        END,
        BUFFERING, // Not in mediaplayer state but useful
        ERROR
    }

    public static enum StopState {
        DONE,
        FORCE_STOPPED,
        NETWORK_UNAVAILABLE,
        UNKNOWN_ERROR
    }

    private static enum YTPState {
        IDLE,
        SUSPENDED,
    }

    public static class Video {
        public final String   title;
        public final String   videoId;
        public final int      volume;
        public final int      playtime; // This is to workaround not-correct value returns from getDuration() function
                           //   of youtube player (Seconds).
        public Video(String aVideoId, String aTitle,
                     int aVolume, int aPlaytime) {
            videoId = aVideoId;
            title = aTitle;
            playtime = aPlaytime;
            volume = aVolume;
        }
    }


    private static class NrElem {
        public int      n;
        public Object   tag;
        NrElem(int aN, Object aTag) {
            n = aN;
            tag = aTag;
        }
    }

    public static class TelephonyMonitor extends BroadcastReceiver {
        @Override
        public void
        onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (!action.equals("android.intent.action.PHONE_STATE"))
                return;

            String exst = intent.getExtras().getString(TelephonyManager.EXTRA_STATE);
            if (null == exst) {
                logW("TelephonyMonitor Unexpected broadcast message");
                return;
            }

            if (TelephonyManager.EXTRA_STATE_IDLE.equals(exst)) {
                YTPlayer.get().ytpResumePlaying();
            } else if (TelephonyManager.EXTRA_STATE_RINGING.equals(exst)) {
                YTPlayer.get().ytpSuspendPlaying();
            } else if (TelephonyManager.EXTRA_STATE_OFFHOOK.equals(exst)) {
                // Nothing to do...
            } else {
                logW("TelephonyMonitor Unexpected extra state : " + exst);
                return; // ignore others.
            }
        }
    }

    // This class also for future use.
    public static class NetworkMonitor extends BroadcastReceiver {
        @Override
        public void
        onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (!action.equals(ConnectivityManager.CONNECTIVITY_ACTION))
                return;

            NetworkInfo networkInfo =
                (NetworkInfo)intent.getParcelableExtra(ConnectivityManager.EXTRA_NETWORK_INFO);
            if (networkInfo.isConnected()) {
                logI("YTPlayer : Network connected : " + networkInfo.getType());
                switch (networkInfo.getType()) {
                case ConnectivityManager.TYPE_WIFI:
                    logI("YTPlayer : Network connected : WIFI");
                    break;
                case ConnectivityManager.TYPE_MOBILE:
                    logI("YTPlayer : Network connected : MOBILE");
                    break;
                }
            } else
                logI("YTPlayer : Network lost");
        }
    }

    private class AutoStop implements Runnable {
        @Override
        public void
        run() {
            stopVideos();
        }
    }

    private class StartVideoRecovery implements Runnable {
        private Video v = null;

        void
        cancel() {
            Utils.getUiHandler().removeCallbacks(this);
        }

        // USE THIS FUNCTION
        void
        executeRecoveryStart(Video aV, long delays) {
            eAssert(Utils.isUiThread());
            cancel();
            v = aV;
            if (delays > 0)
                Utils.getUiHandler().postDelayed(this, delays);
            else
                Utils.getUiHandler().post(this);
        }

        void
        executeRecoveryStart(Video aV) {
            executeRecoveryStart(aV, 0);
        }

        // DO NOT run this explicitly.
        @Override
        public
        void run() {
            eAssert(Utils.isUiThread());
            if (null != v)
                startVideo(v, true);
        }
    }


    private static class VideoListManager {
        private Video[]     vs  = null; // video array
        private int         vi  = -1; // video index
        private OnListChangedListener lcListener = null;

        interface OnListChangedListener {
            void onChanged(VideoListManager vm);
        }

        VideoListManager(OnListChangedListener listener) {
            lcListener = listener;
        }

        void
        setOnListChangedListener(OnListChangedListener listener) {
            eAssert(Utils.isUiThread());
            lcListener = listener;
        }

        void
        clearOnListChangedListener() {
            eAssert(Utils.isUiThread());
            lcListener = null;
        }

        void
        notifyToListChangedListener() {
            if (null != lcListener)
                lcListener.onChanged(this);
        }

        boolean
        hasActiveVideo() {
            eAssert(Utils.isUiThread());
            return null != getActiveVideo();
        }

        boolean
        hasNextVideo() {
            eAssert(Utils.isUiThread());
            return hasActiveVideo()
                   && vi < (vs.length - 1);
        }

        boolean
        hasPrevVideo() {
            eAssert(Utils.isUiThread());
            return hasActiveVideo() && 0 < vi;
        }

        void
        reset() {
            eAssert(Utils.isUiThread());
            vs = null;
            vi = -1;
            notifyToListChangedListener();
        }

        void
        setVideoList(Video[] aVs) {
            eAssert(Utils.isUiThread());
            vs = aVs;
            if (null == vs || 0 >= vs.length)
                reset();
            else if(vs.length > 0)
                vi = 0;
            notifyToListChangedListener();
        }

        Video[]
        getVideoList() {
            eAssert(Utils.isUiThread());
            return vs;
        }

        void
        appendVideo(Video v) {
            eAssert(Utils.isUiThread());
            Video[] newvs = new Video[vs.length + 1];
            System.arraycopy(vs, 0, newvs, 0, vs.length);
            newvs[newvs.length - 1] = v;
            // assigning reference is atomic operation in JAVA!
            vs = newvs;
            notifyToListChangedListener();
        }

        int
        getActiveVideoIndex() {
            return vi;
        }

        Video
        getActiveVideo() {
            eAssert(Utils.isUiThread());
            if (null != vs && 0 <= vi && vi < vs.length)
                return vs[vi];
            return null;
        }

        Video
        getNextVideo() {
            eAssert(Utils.isUiThread());
            if (!hasNextVideo())
                return null;
            return vs[vi + 1];
        }

        boolean
        moveToFist() {
            eAssert(Utils.isUiThread());
            if (hasActiveVideo()) {
                    vi = 0;
                    return true;
            }
            return false;
        }

        boolean
        moveToNext() {
            eAssert(Utils.isUiThread());
            if (hasActiveVideo()
                && vi < (vs.length - 1)) {
                vi++;
                return true;
            }
            return false;
        }

        boolean
        moveToPrev() {
            eAssert(Utils.isUiThread());
            if (hasActiveVideo()
                && vi > 0) {
                vi--;
                return true;
            }
            return false;
        }
    }

    // ========================================================================
    //
    //
    //
    // ========================================================================
    private void
    acquireLocks() {
        if (null != mWl)
            return; // already locked nothing to do

        eAssert(null == mWfl);
        mWl = ((PowerManager)Utils.getAppContext().getSystemService(Context.POWER_SERVICE))
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WLTAG);
        // Playing youtube requires high performance wifi for high quality media play.
        mWfl = ((WifiManager)Utils.getAppContext().getSystemService(Context.WIFI_SERVICE))
                .createWifiLock(WifiManager.WIFI_MODE_FULL, WLTAG);
        mWl.acquire();
        mWfl.acquire();
    }

    private void
    releaseLocks() {
        if (null == mWl)
            return;

        eAssert(null != mWfl);
        mWl.release();
        mWfl.release();

        mWl = null;
        mWfl = null;
    }

    // ========================================================================
    //
    // Media Player Interfaces
    //
    // ========================================================================
    private boolean
    mpIsAvailable() {
        return null != mMp
               && MPState.END != mpGetState()
               && MPState.INVALID != mpGetState();
    }
    private void
    mpSetState(MPState newState) {
        logD("MusicPlayer : State : " + mMpS.name() + " => " + newState.name());
        MPState old = mMpS;
        mMpS = newState;
        onMpStateChanged(old, newState);
    }

    private MPState
    mpGetState() {
        return mMpS;
    }

    private void
    initMediaPlayer(MediaPlayer mp) {
        mp.setOnBufferingUpdateListener(this);
        mp.setOnCompletionListener(this);
        mp.setOnVideoSizeChangedListener(this);
        mp.setOnSeekCompleteListener(this);
        mp.setOnErrorListener(this);
        mp.setOnInfoListener(this);
        mp.setScreenOnWhilePlaying(false);
        mp.setAudioStreamType(AudioManager.STREAM_MUSIC);
        mp.setOnPreparedListener(this);
    }

    private void
    mpNewInstance() {
        mMp = new MediaPlayer();
        mMpVol = Policy.DEFAULT_VIDEO_VOLUME;
        initMediaPlayer(mMp);
    }

    private MediaPlayer
    mpGet() {
        return mMp;
    }

    private void
    mpSetDataSource(Uri uri) throws IOException {
        mMp.setDataSource(Utils.getAppContext(), uri);
        mpSetState(MPState.INITIALIZED);
    }

    private void
    mpSetDataSource(String path) throws IOException {
        mMp.setDataSource(path);
        mpSetState(MPState.INITIALIZED);
    }

    private void
    mpPrepareAsync() {
        mpSetState(MPState.PREPARING);
        mMp.prepareAsync();
    }

    private void
    mpRelease() {
        if (null == mMp || MPState.END == mpGetState())
            return;


        if (MPState.ERROR != mMpS && mMp.isPlaying())
            mMp.stop();

        // Why run at another thread?
        // Sometimes mMp.release takes too long time or may never return.
        // Even in this case, ANR is very annoying to user.
        // So, this is a kind of workaround for these cases.
        final MediaPlayer mp = mMp;
        new Thread(new Runnable() {
            @Override
            public void run() {
                mp.release();
            }
        }).start();
        mMp = null;
        mpSetState(MPState.END);
    }

    private void
    mpReset() {
        mMp.reset();
        mpSetState(MPState.IDLE);
    }

    private void
    mpSetVolume(int vol) {
        if (null == mMp)
            return;

        float volf = vol/100.0f;
        mMpVol = vol;
        mMp.setVolume(volf, volf);
    }

    private int
    mpGetVolume() {
        switch(mMpS) {
        case END:
        case ERROR:
        case PREPARING:
            return Policy.DEFAULT_VIDEO_VOLUME;
        }
        return mMpVol;
    }

    private int
    mpGetCurrentPosition() {
        if (null == mMp)
            return 0;

        switch (mpGetState()) {
        case ERROR:
        case END:
            return 0;
        }
        return mMp.getCurrentPosition();
    }

    private int
    mpGetDuration() {
        if (null == mMp)
            return 0;

        switch(mpGetState()) {
        case PREPARED:
        case STARTED:
        case PAUSED:
        case STOPPED:
        case PLAYBACK_COMPLETED:
            return mMp.getDuration();
        }
        return 0;
    }

    private boolean
    mpIsPlaying() {
        //logD("MPlayer - isPlaying");
        if (null == mMp)
            return false;

        return mMp.isPlaying();
    }

    private void
    mpPause() {
        logD("MPlayer - pause");
        if (null == mMp)
            return;

        mMp.pause();
        mpSetState(MPState.PAUSED);
    }

    private void
    mpSeekTo(int pos) {
        logD("MPlayer - seekTo : " + pos);
        if (null == mMp)
            return;

        mMp.seekTo(pos);
    }

    private void
    mpStart() {
        logD("MPlayer - start");
        if (null == mMp)
            return;

        if (ytpIsSuspended())
            return;

        mMp.start();
        mpSetState(MPState.STARTED);
    }

    private void
    mpStop() {
        if (null == mMp)
            return;

        logD("MPlayer - stop");
        mMp.stop();
        mpSetState(MPState.STOPPED);
    }
    // ========================================================================
    //
    // Suspending/Resuming Control
    //
    // ========================================================================
    private void
    ytpSuspendPlaying() {
        eAssert(Utils.isUiThread());
        pauseVideo();
        mYtpS = YTPState.SUSPENDED;
    }

    private void
    ytpResumePlaying() {
        eAssert(Utils.isUiThread());
        mYtpS = YTPState.IDLE;
    }

    private boolean
    ytpIsSuspended() {
        eAssert(Utils.isUiThread());
        return YTPState.SUSPENDED == mYtpS;
    }

    // ========================================================================
    //
    // General Control
    //
    // ========================================================================
    private void
    onMpStateChanged(MPState from, MPState to) {
        if (from == to)
            return;

        if (null != mPStateLsnr)
            mPStateLsnr.onStateChanged(from, to);

        switch (to) {
        case PAUSED:
        case INVALID:
            releaseLocks();
            break;

        case STARTED:
            acquireLocks();
            break;

        case STOPPED:
            if (null != mLoader)
                mLoader.close();
            break;
        }
    }

    private int
    getVideoQualityScore() {
        switch (Utils.getPrefQuality()) {
        case LOW:
            return YTHacker.getQScorePreferLow(YTHacker.YTQUALITY_SCORE_LOWEST);

        case NORMAL:
            return YTHacker.getQScorePreferHigh(YTHacker.YTQUALITY_SCORE_MIDLOW);
        }
        eAssert(false);
        return YTHacker.getQScorePreferLow(YTHacker.YTQUALITY_SCORE_LOWEST);
    }

    private File
    getCachedVideo(String ytvid) {
        // Only mp4 is supported by YTHacker.
        // WebM and Flv is not supported directly in Android's MediaPlayer.
        // So, Mpeg is only option we can choose.
        return new File(Policy.APPDATA_CACHEDIR + ytvid + ".mp4");
    }

    private Video[]
    getVideos(Cursor c,
              int coliTitle,  int coliUrl,
              int coliVolume, int coliPlaytime,
              boolean shuffle) {
        if (!c.moveToFirst())
            return new Video[0];

        Video[] vs = new Video[c.getCount()];

        int i = 0;
        if (!shuffle) {
            do {
                vs[i++] = new Video(c.getString(coliUrl),
                                    c.getString(coliTitle),
                                    c.getInt(coliVolume),
                                    c.getInt(coliPlaytime));
            } while (c.moveToNext());
            Arrays.sort(vs, sVideoTitleComparator);
        } else {
            // This is shuffled case!
            Random r = new Random(System.currentTimeMillis());
            NrElem[] nes = new NrElem[c.getCount()];
            do {
                nes[i++] = new NrElem(r.nextInt(),
                                      new Video(c.getString(coliUrl),
                                                c.getString(coliTitle),
                                                c.getInt(coliVolume),
                                                c.getInt(coliPlaytime)));
            } while (c.moveToNext());
            Arrays.sort(nes, sNrElemComparator);
            for (i = 0; i < nes.length; i++)
                vs[i] = (Video)nes[i].tag;
        }
        return vs;
    }

    private void
    cachingVideo(final String vid) {
        mYtDnr.close();

        mYtDnr = new YTDownloader();
        YTDownloader.DownloadDoneReceiver rcvr = new DownloadDoneReceiver() {
            @Override
            public void
            downloadDone(YTDownloader downloader, DnArg arg, Err err) {
                if (mYtDnr != downloader) {
                    downloader.close();
                    return;
                }

                int retryTag = (Integer)downloader.getTag();
                if (Err.NO_ERR != err
                    && Utils.isNetworkAvailable()
                    && retryTag > 0) {
                    // retry.
                    retryTag--;
                    downloader.setTag(retryTag);
                    try {
                        Thread.sleep(500);
                    } catch (InterruptedException e) {}
                    downloader.download(vid, getCachedVideo(vid), getVideoQualityScore());
                } else
                    downloader.close();
                // Ignore other cases even if it is fails.
            }
        };

        mYtDnr.open("", rcvr);
        // to retry in case of YTHTTPGET.
        mYtDnr.setTag(Policy.NETOWRK_CONN_RETRY);
        // NOTE
        // Only mp4 is supported at YTHacker!
        // So, YTDownloader also supports only mpeg4
        mYtDnr.download(vid, getCachedVideo(vid), getVideoQualityScore());
    }

    private void
    cleanCache(boolean allClear) {
        if (!mVlm.hasActiveVideo())
            return;

        HashSet<String> skipSet = new HashSet<String>();
        if (!allClear) {
            // delete all cached videos except for
            //   current and next video.
            // DO NOT delete cache directory itself!
            skipSet.add(sCacheDir.getAbsolutePath());
            skipSet.add(getCachedVideo(mVlm.getActiveVideo().videoId).getAbsolutePath());
            Video nextVid = mVlm.getNextVideo();
            if (null != nextVid)
                skipSet.add(getCachedVideo(nextVid.videoId).getAbsolutePath());
        }
        Utils.removeFileRecursive(sCacheDir, skipSet);
    }

    /**
     * player will be stopped after 'millis'
     * @param millis
     *   0 for disable autostop
     */
    private void
    setAutoStop(long millis) {
        Utils.getUiHandler().removeCallbacks(mAutoStop);
        if (millis > 0)
            Utils.getUiHandler().postDelayed(mAutoStop, millis);
    }

    private void
    prepareNext() {
        if (!mVlm.hasNextVideo())
            return;

        cachingVideo(mVlm.getNextVideo().videoId);
    }

    private void
    prepareVideoStreaming(final String videoId) {
        logI("Prepare Video Streaming : " + videoId);

        YTHacker.YtHackListener listener = new YTHacker.YtHackListener() {
            @Override
            public void
            onPreHack(YTHacker ythack, String ytvid, Object user) {
            }

            @Override
            public void
            onPostHack(final YTHacker ythack, Err result, final NetLoader loader,
                       String ytvid, Object user) {
                if (mYtHack != ythack) {
                    // Another try is already done.
                    // So, this response should be ignored.
                    logD("YTPlayer Old Youtube connection is finished. Ignored");
                    loader.close();
                    return;
                }

                mYtHack = null;
                if (null != mLoader)
                    mLoader.close();
                mLoader = loader;

                if (Err.NO_ERR != result) {
                    logW("YTPlayer YTHack Fails : " + result.name());
                    mStartVideoRecovery.executeRecoveryStart(mVlm.getActiveVideo());
                    return;
                }

                YTHacker.YtVideo ytv = ythack.getVideo(getVideoQualityScore());
                try {
                    mpSetDataSource(Uri.parse(ytv.url));
                } catch (IOException e) {
                    logW("YTPlayer SetDataSource IOException : " + e.getMessage());
                    mStartVideoRecovery.executeRecoveryStart(mVlm.getActiveVideo(), 500);
                    return;
                }

                // Update DB at this moment.
                // It's not perfectly right moment but it's fair enough
                // But, this case can be ignored.
                new Thread(new Runnable() {
                    @Override
                    public void run() {
                        mDb.updateVideo(DB.ColVideo.VIDEOID, videoId,
                                        DB.ColVideo.TIME_PLAYED, System.currentTimeMillis());
                    }

                }).start();
                mpPrepareAsync();
            }

            @Override
            public void
            onHackCancelled(YTHacker ythack, String ytvid, Object user) {
                if (mYtHack != ythack) {
                    logD("Old Youtube connection is cancelled. Ignored");
                    return;
                }

                mYtHack = null;
            }
        };
        mYtHack = new YTHacker(videoId, null, listener);
        mYtHack.startAsync();
    }

    private void
    prepareCachedVideo(File cachedVid) {
        logI("Prepare Cached video: " + cachedVid.getAbsolutePath());
        // We have cached one.
        // So play in local!
        try {
            mpSetDataSource(cachedVid.getAbsolutePath());
        } catch (IOException e) {
            // Something wrong at cached file.
            // Clean cache and try again - next time as streaming!
            cleanCache(true);
            logW("YTPlayer SetDataSource to Cached File IOException : " + e.getMessage());
            mStartVideoRecovery.executeRecoveryStart(mVlm.getActiveVideo());
        }
        mpPrepareAsync();

        // In this case, player doens't need to buffering video.
        // So, player doens't need to wait until buffering is 100% done.
        // Therefore let's prepare next video immediately.
        prepareNext();
    }

    private void
    startVideo(Video v, boolean recovery) {
        if (null != v)
            startVideo(v.videoId, v.volume, recovery);
    }

    private void
    startVideo(final String videoId, final int volume, boolean recovery) {
        eAssert(0 <= volume && volume <= 100);

        // Clean recovery try
        mStartVideoRecovery.cancel();

        // Whenever start videos, try to clean cache.
        cleanCache(false);

        if (recovery) {
            mErrRetry--;
            if (mErrRetry <= 0) {
                if (Utils.isNetworkAvailable()) {
                    if (mVlm.hasNextVideo()) {
                        if (mVlm.hasActiveVideo()) {
                            Video v = mVlm.getActiveVideo();
                            logW("YTPlayer Recovery video play Fails");
                            logW("    ytvid : " + v.videoId);
                            logW("    title : " + v.title);
                        }
                        startNext(); // move to next video.
                    } else
                        stopPlay(StopState.UNKNOWN_ERROR);
                } else
                    stopPlay(StopState.NETWORK_UNAVAILABLE);
                return;
            }
        } else
            mErrRetry = PLAYER_ERR_RETRY;

        // Stop if player is already running.
        mpStop();
        mpRelease();
        mpNewInstance();
        mpReset();
        mpSetVolume(volume);

        File cachedVid = getCachedVideo(videoId);
        if (cachedVid.exists() && cachedVid.canRead())
            prepareCachedVideo(cachedVid);
        else {
            if (!Utils.isNetworkAvailable())
                mStartVideoRecovery.executeRecoveryStart(new Video(videoId, "", volume, 0), 1000);
            else
                prepareVideoStreaming(videoId);
        }

    }

    private void
    startNext() {
        if (!mVlm.hasActiveVideo())
            return; // do nothing

        if (mVlm.moveToNext())
            startVideo(mVlm.getActiveVideo(), false);
        else
            stopPlay(StopState.DONE);
    }

    private void
    startPrev() {
        if (!mVlm.hasActiveVideo())
            return; // do nothing

        if (mVlm.moveToPrev())
            startVideo(mVlm.getActiveVideo(), false);
        else
            stopPlay(StopState.DONE);
    }

    private void
    stopPlay(StopState st) {
        logD("VideoView - playDone : forceStop (" + st.name() + ")");
        if (null != mYtHack)
            mYtHack.forceCancel();

        if (StopState.DONE == st
            && Utils.isPrefRepeat()) {
            if (mVlm.moveToFist()) {
                startVideo(mVlm.getActiveVideo(), false);
                return;
            }
        }
        // Play is already stopped.
        // So, auto stop should be inactive here.
        setAutoStop(0);

        mpStop();
        mpRelease();
        releaseLocks();
        mVlm.reset();
        mYtDnr.close();
        mErrRetry = PLAYER_ERR_RETRY;

        // This should be called before changing title because
        //   title may be changed in onMpStateChanged().
        // We need to overwrite title message.
        mpSetState(MPState.INVALID);

        if (null != mVStateLsnr)
            mVStateLsnr.onStopped(st);
    }
    // ============================================================================
    //
    // Package interfaces
    //
    // ============================================================================
    void
    pauseVideo() {
        if (isVideoPlaying()
            && MPState.PREPARING != mpGetState())
            mpPause();
    }

    void
    startVideo() {
        if (isVideoPlaying()
            && MPState.PREPARING != mpGetState())
            mpStart();
    }

    // ============================================================================
    //
    // Override for "MediaPlayer.*"
    //
    // ============================================================================
    @Override
    public void
    onBufferingUpdate (MediaPlayer mp, int percent) {
        //logD("MPlayer - onBufferingUpdate : " + percent + " %");
        // See comments around MEDIA_INFO_BUFFERING_START in onInfo()
        //mpSetState(MPState.BUFFERING);

        if (mLastBuffering < Policy.YTPLAYER_CACHING_TRIGGER_POINT
            && Policy.YTPLAYER_CACHING_TRIGGER_POINT <= percent)
            // This is the first moment that buffering is reached to 100%
            prepareNext();

        if (null != mPStateLsnr)
            mPStateLsnr.onBufferingChanged(percent);

        mLastBuffering = percent;
    }

    @Override
    public void
    onCompletion(MediaPlayer mp) {
        logD("MPlayer - onCompletion");
        mpSetState(MPState.PLAYBACK_COMPLETED);
        startNext();
    }

    @Override
    public void
    onPrepared(MediaPlayer mp) {
        // OnPreparedListener is very difficult to handle because of it's async-nature.
        // We may request some other works to media player even in "preparing" state
        //   - ex. Stop and start new preparation after create new media player instance.
        // Especially, in this case, player should be able to tell that which callback is for which request.
        // To do this, ytplayer should compare that current media player is prepared one or not.
        if (mp != mpGet()) {
            // ignore.
            logD("MPlayer - old invalid player is prepared.");
            return;
        }

        logD("MPlayer - onPrepared");
        mpSetState(MPState.PREPARED);
        mpStart(); // auto start
    }

    @Override
    public void
    onVideoSizeChanged(MediaPlayer mp, int width, int height) {
        logD("MPlayer - onVideoSizeChanged");
    }

    @Override
    public void
    onSeekComplete(MediaPlayer mp) {
        logD("MPlayer - onSeekComplete");
    }

    @Override
    public boolean
    onError(MediaPlayer mp, int what, int extra) {
        boolean tryAgain = true;
        mpSetState(MPState.ERROR);
        switch (what) {
        case MediaPlayer.MEDIA_ERROR_NOT_VALID_FOR_PROGRESSIVE_PLAYBACK:
            logD("MPlayer - onError : NOT_VALID_FOR_PROGRESSIVE_PLAYBACK");
            tryAgain = false;
            break;

        case MediaPlayer.MEDIA_ERROR_SERVER_DIED:
            logD("MPlayer - onError : MEDIA_ERROR_SERVER_DIED");
            break;

        case MediaPlayer.MEDIA_ERROR_UNKNOWN:
            logD("MPlayer - onError : UNKNOWN");
            break;

        default:
            logD("MPlayer - onError");
        }

        if (tryAgain && mVlm.hasActiveVideo()) {
            logD("MPlayer - Try to recover!");
            startVideo(mVlm.getActiveVideo(), true);
        } else
            stopPlay(StopState.UNKNOWN_ERROR);

        return true; // DO NOT call onComplete Listener.
    }

    @Override
    public boolean
    onInfo(MediaPlayer mp, int what, int extra) {
        logD("MPlayer - onInfo : " + what);
        switch (what) {
        case MediaPlayer.MEDIA_INFO_VIDEO_TRACK_LAGGING:
            break;

        case MediaPlayer.MEDIA_INFO_NOT_SEEKABLE:
            break;

        case MediaPlayer.MEDIA_INFO_METADATA_UPDATE:
            break;

        case MediaPlayer.MEDIA_INFO_BUFFERING_START:
            // NOTE
            // In case of progressive download, media player tries to buffering continuously.
            // Even if media is playing, media info keep notifying 'buffering'
            // This is not expected behavior of player.
            // So, just ignore buffering state.
            //mpSetState(MPState.BUFFERING);
            break;

        case MediaPlayer.MEDIA_INFO_BUFFERING_END:
            if (null != mPStateLsnr)
                mPStateLsnr.onBufferingChanged(100);
            break;

        case MediaPlayer.MEDIA_INFO_BAD_INTERLEAVING:
            break;

        case MediaPlayer.MEDIA_INFO_UNKNOWN:
            break;

        default:
        }
        return false;
    }

    // ============================================================================
    //
    // Package interfaces (Usually for YTPLayerUI)
    //
    // ============================================================================
    void
    setVideosStateListener(VideosStateListener listener) {
        eAssert(null != listener);
        mVStateLsnr = listener;
    }

    void
    setPlayerStateListener(PlayerStateListener listener) {
        eAssert(null != listener);
        mPStateLsnr = listener;
    }

    boolean
    hasNextVideo() {
        return mVlm.hasNextVideo();
    }

    void
    startNextVideo() {
        startNext();
    }

    boolean
    hasPrevVideo() {
        return mVlm.hasPrevVideo();
    }

    void
    startPrevVideo() {
        startPrev();
    }

    Video
    getActiveVideo() {
        return mVlm.getActiveVideo();
    }

    Video[]
    getVideoList() {
        return mVlm.getVideoList();
    }

    int
    getActiveVideoIndex() {
        return mVlm.getActiveVideoIndex();
    }

    MPState
    playerGetState() {
        return mpGetState();
    }

    void
    playerStart() {
        mpStart();
    }

    void
    playerPause() {
        mpPause();
    }

    /**
     *
     * @param pos
     *   milliseconds.
     */
    void
    playerSeekTo(int pos) {
        mpSeekTo(pos);
    }


    /**
     * Get duration(milliseconds) of current active video
     * @return
     */
    int
    playerGetDuration() {
        return mpGetDuration();
    }

    /**
     * Get current position(milliseconds) from start
     * @return
     */
    int
    playerGetPosition() {
        return mpGetCurrentPosition();
    }

    int
    playerGetVolume() {
        return mpGetVolume();
    }


    /**
     * Set volume of video-on-play
     * @param vol
     */
    void
    playerSetVolume(int vol) {
        eAssert(0 <= vol && vol <= 100);
        // Implement this
        if (isVideoPlaying())
            mpSetVolume(vol);
    }

    // ============================================================================
    //
    // Public interfaces
    //
    // ============================================================================
    private YTPlayer() {
        mVlm = new VideoListManager(new VideoListManager.OnListChangedListener() {
            @Override
            public void onChanged(VideoListManager vm) {
                eAssert(Utils.isUiThread());
                if (null != mVStateLsnr)
                    mVStateLsnr.onChanged();
            }
        });
    }

    public static YTPlayer
    get() {
        if (null == sInstance)
            sInstance = new YTPlayer();
        return sInstance;
    }

    public Err
    setController(Context context, ViewGroup playerv, ViewGroup playerLDrawer) {
        return mUi.setController(context, playerv, playerLDrawer);
    }

    public void
    unsetController(Context context) {
        mUi.unsetController(context);
    }

    public void
    changeVideoVolume(final String title, final String videoId) {
        mUi.changeVideoVolume(title, videoId);
    }

    public boolean
    hasActiveVideo() {
        return mVlm.hasActiveVideo();
    }

    public boolean
    isVideoPlaying() {
        return mVlm.hasActiveVideo()
               && MPState.ERROR != mMpS
               && MPState.END != mMpS;
    }

    /**
     * Get volume of video-on-play
     * @return
     *   -1 : for error
     */
    public int
    getVideoVolume() {
        if (isVideoPlaying())
            return mpGetVolume();
        return DB.INVALID_VOLUME;
    }

    public void
    startVideos(final Video[] vs) {
        eAssert(Utils.isUiThread());

        if (null == vs || vs.length <= 0)
            return;

        acquireLocks();
        setAutoStop(Utils.getPrefAutoStopMillis());

        mVlm.setVideoList(vs);

        if (mVlm.moveToFist()) {
            startVideo(mVlm.getActiveVideo(), false);
            if (null != mVStateLsnr)
                mVStateLsnr.onStarted();
        }
    }

    public void
    startVideos(final Cursor c,
                final int coliUrl,      final int coliTitle,
                final int coliVolume,   final int coliPlaytime,
                final boolean shuffle) {
        eAssert(Utils.isUiThread());

        new Thread(new Runnable() {
            @Override
            public void run() {
                final Video[] vs = getVideos(c, coliTitle, coliUrl, coliVolume, coliPlaytime, shuffle);
                Utils.getUiHandler().post(new Runnable() {
                    @Override
                    public void run() {
                        startVideos(vs);
                    }
                });
                c.close();
            }
        }).start();
    }

    /**
     *
     * @param v
     * @return
     *   false if error, otherwise true
     */
    public boolean
    appendToCurrentPlayQ(Video v) {
        if (!mVlm.hasActiveVideo())
            return false;

        mVlm.appendVideo(v);
        return true;
    }

    public void
    stopVideos() {
        stopPlay(StopState.FORCE_STOPPED);
    }

    public String
    getPlayVideoYtId() {
        if (isVideoPlaying())
            return mVlm.getActiveVideo().videoId;
        return null;
    }

}
