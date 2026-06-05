package com.example.myapp;

import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import java.util.List;
import com.example.myapp.model.StreamOption;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import androidx.media3.ui.PlayerView;

import com.example.myapp.extractor.YouTubeExtractorService;
import com.example.myapp.model.VideoItem;
import com.google.android.material.button.MaterialButton;

import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers;
import io.reactivex.rxjava3.disposables.CompositeDisposable;

/**
 * Full-screen ExoPlayer activity.
 * Uses DefaultHttpDataSource (not OkHttpDataSource) with a desktop User-Agent,
 * matching the approach used in the working MaterialTube reference project.
 */
@SuppressWarnings("deprecation")   // setSystemUiVisibility on API 28
public class PlayerActivity extends AppCompatActivity {

    public static final String EXTRA_VIDEO = "extra_video";
    private static final String TAG = "PlayerActivity";

    private static final String USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
        "AppleWebKit/537.36 (KHTML, like Gecko) " +
        "Chrome/120.0.0.0 Safari/537.36";

    private PlayerView     mPlayerView;
    private View           mLoadingOverlay;
    private View           mErrorOverlay;
    private TextView       mErrorText;
    private MaterialButton mRetryBtn;
    private MaterialButton mQualityBtn;

    private ExoPlayer mPlayer;
    private VideoItem mVideo;
    private List<StreamOption> mStreamOptions;

    private final CompositeDisposable mDisposables = new CompositeDisposable();

    // ── Lifecycle ──────────────────────────────────────────────────────────

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Fullscreen immersive — API 28 compatible
        getWindow().getDecorView().setSystemUiVisibility(
            View.SYSTEM_UI_FLAG_FULLSCREEN
            | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
            | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
        );

        setContentView(R.layout.activity_player);

        mPlayerView     = findViewById(R.id.player_view);
        mLoadingOverlay = findViewById(R.id.loading_overlay);
        mErrorOverlay   = findViewById(R.id.error_overlay);
        mErrorText      = findViewById(R.id.error_text);
        mRetryBtn       = findViewById(R.id.retry_button);
        mQualityBtn     = findViewById(R.id.quality_button);

        Object extra = getIntent().getSerializableExtra(EXTRA_VIDEO);
        if (!(extra instanceof VideoItem)) { finish(); return; }
        mVideo = (VideoItem) extra;

        mRetryBtn.setOnClickListener(v -> {
            mErrorOverlay.setVisibility(View.GONE);
            loadAndPlay();
        });

        mQualityBtn.setOnClickListener(v -> showQualityDialog());

        buildPlayer();
        loadAndPlay();
    }

    @Override protected void onStart()  { super.onStart();  if (mPlayer != null) mPlayer.play(); }
    @Override protected void onStop()   { super.onStop();   if (mPlayer != null) mPlayer.pause(); }

    @Override
    protected void onDestroy() {
        mDisposables.clear();
        releasePlayer();
        super.onDestroy();
    }

    // ── Player setup ──────────────────────────────────────────────────────

    private void buildPlayer() {
        // Use DefaultHttpDataSource with desktop User-Agent — same as working MaterialTube project
        DefaultHttpDataSource.Factory dataSourceFactory =
            new DefaultHttpDataSource.Factory()
                .setUserAgent(USER_AGENT)
                .setAllowCrossProtocolRedirects(true);

        mPlayer = new ExoPlayer.Builder(this)
            .setMediaSourceFactory(new DefaultMediaSourceFactory(dataSourceFactory))
            .build();

        mPlayerView.setPlayer(mPlayer);
        mPlayerView.setUseController(true);
        mPlayerView.setControllerAutoShow(true);
        mPlayerView.setControllerShowTimeoutMs(3000);
        
        mPlayerView.setControllerVisibilityListener(new PlayerView.ControllerVisibilityListener() {
            @Override
            public void onVisibilityChanged(int visibility) {
                if (mStreamOptions != null && mStreamOptions.size() > 1) {
                    mQualityBtn.setVisibility(visibility);
                } else {
                    mQualityBtn.setVisibility(View.GONE);
                }
            }
        });

        mPlayer.addListener(new Player.Listener() {
            @Override
            public void onPlaybackStateChanged(int state) {
                mLoadingOverlay.setVisibility(
                    state == Player.STATE_BUFFERING ? View.VISIBLE : View.GONE);
                if (state == Player.STATE_READY)
                    mErrorOverlay.setVisibility(View.GONE);
            }

            @Override
            public void onPlayerError(PlaybackException e) {
                Log.e(TAG, "Playback error", e);
                mLoadingOverlay.setVisibility(View.GONE);
                mErrorText.setText(getString(R.string.error_load) + "\n" +
                    (e.getMessage() != null ? e.getMessage() : "Unknown error"));
                mErrorOverlay.setVisibility(View.VISIBLE);
            }
        });
    }

    // ── Playback ──────────────────────────────────────────────────────────

    private void loadAndPlay() {
        mLoadingOverlay.setVisibility(View.VISIBLE);
        mErrorOverlay.setVisibility(View.GONE);

        mDisposables.add(
            YouTubeExtractorService.getInstance()
                .getStreamOptions(mVideo.getVideoId())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(
                    options -> {
                        mStreamOptions = options;
                        if (options.size() > 1) {
                            mQualityBtn.setVisibility(View.VISIBLE);
                        } else {
                            mQualityBtn.setVisibility(View.GONE);
                        }
                        startPlayback(options.get(0).getUrl());
                    },
                    err -> {
                        Log.e(TAG, "Extraction error", err);
                        mLoadingOverlay.setVisibility(View.GONE);
                        mErrorText.setText(getString(R.string.error_load) + "\n" +
                            (err.getMessage() != null ? err.getMessage() : "Extraction failed"));
                        mErrorOverlay.setVisibility(View.VISIBLE);
                    }
                )
        );
    }

    private void startPlayback(String url) {
        if (mPlayer == null) return;
        long currentPos = mPlayer.getCurrentPosition();
        Log.d(TAG, "Playing: " + url.substring(0, Math.min(80, url.length())));
        mPlayer.setMediaItem(MediaItem.fromUri(Uri.parse(url)));
        mPlayer.prepare();
        if (currentPos > 0) {
            mPlayer.seekTo(currentPos);
        }
        mPlayer.setPlayWhenReady(true);
    }

    private void showQualityDialog() {
        if (mStreamOptions == null || mStreamOptions.isEmpty()) return;
        String[] items = new String[mStreamOptions.size()];
        for (int i = 0; i < mStreamOptions.size(); i++) {
            items[i] = mStreamOptions.get(i).getResolution();
        }
        new AlertDialog.Builder(this)
            .setTitle("Select Quality")
            .setItems(items, (dialog, which) -> {
                startPlayback(mStreamOptions.get(which).getUrl());
            })
            .show();
    }

    private void releasePlayer() {
        if (mPlayer != null) {
            mPlayer.clearVideoSurface();
            mPlayer.release();
            mPlayer = null;
        }
    }
}
