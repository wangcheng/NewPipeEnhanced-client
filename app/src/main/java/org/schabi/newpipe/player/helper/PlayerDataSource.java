package org.schabi.newpipe.player.helper;

import android.content.Context;
import android.net.Uri;

import com.google.android.exoplayer2.source.MediaSource;
import com.google.android.exoplayer2.source.ProgressiveMediaSource;
import com.google.android.exoplayer2.source.SingleSampleMediaSource;
import com.google.android.exoplayer2.source.dash.DashMediaSource;
import com.google.android.exoplayer2.source.dash.DefaultDashChunkSource;
import com.google.android.exoplayer2.source.hls.HlsMediaSource;
import com.google.android.exoplayer2.source.hls.playlist.DefaultHlsPlaylistTracker;
import com.google.android.exoplayer2.source.smoothstreaming.DefaultSsChunkSource;
import com.google.android.exoplayer2.source.smoothstreaming.SsMediaSource;
import com.google.android.exoplayer2.upstream.DataSource;
import com.google.android.exoplayer2.upstream.DefaultDataSource;
import com.google.android.exoplayer2.upstream.DefaultHttpDataSource;
import com.google.android.exoplayer2.upstream.DefaultLoadErrorHandlingPolicy;
import com.google.android.exoplayer2.upstream.ResolvingDataSource;
import com.google.android.exoplayer2.upstream.TransferListener;
import com.grack.nanojson.JsonObject;
import com.grack.nanojson.JsonParser;
import com.grack.nanojson.JsonParserException;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.schabi.newpipe.DownloaderImpl;
import org.schabi.newpipe.extractor.downloader.Response;
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException;
import org.schabi.newpipe.extractor.services.niconico.NiconicoService;
import org.schabi.newpipe.extractor.services.niconico.extractors.NiconicoDMCPayloadBuilder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import com.google.android.exoplayer2.source.ProgressiveMediaSource;
import com.google.android.exoplayer2.source.SingleSampleMediaSource;
import com.google.android.exoplayer2.source.dash.DashMediaSource;
import com.google.android.exoplayer2.source.dash.DefaultDashChunkSource;
import com.google.android.exoplayer2.source.hls.HlsMediaSource;
import com.google.android.exoplayer2.source.hls.playlist.DefaultHlsPlaylistTracker;
import com.google.android.exoplayer2.source.hls.playlist.HlsPlaylistParserFactory;
import com.google.android.exoplayer2.source.smoothstreaming.DefaultSsChunkSource;
import com.google.android.exoplayer2.source.smoothstreaming.SsMediaSource;
import com.google.android.exoplayer2.upstream.DataSource;
import com.google.android.exoplayer2.upstream.DefaultDataSource;
import com.google.android.exoplayer2.upstream.DefaultHttpDataSource;
import com.google.android.exoplayer2.upstream.TransferListener;

import org.schabi.newpipe.extractor.services.youtube.dashmanifestcreators.YoutubeOtfDashManifestCreator;
import org.schabi.newpipe.extractor.services.youtube.dashmanifestcreators.YoutubePostLiveStreamDvrDashManifestCreator;
import org.schabi.newpipe.extractor.services.youtube.dashmanifestcreators.YoutubeProgressiveDashManifestCreator;
import org.schabi.newpipe.player.datasource.YoutubeHttpDataSource;

import java.util.Map;

public class PlayerDataSource {

    public static final int LIVE_STREAM_EDGE_GAP_MILLIS = 10000;

    /**
     * An approximately 4.3 times greater value than the
     * {@link DefaultHlsPlaylistTracker#DEFAULT_PLAYLIST_STUCK_TARGET_DURATION_COEFFICIENT default}
     * to ensure that (very) low latency livestreams which got stuck for a moment don't crash too
     * early.
     */
    private static final double PLAYLIST_STUCK_TARGET_DURATION_COEFFICIENT = 15;

    /**
     * The maximum number of generated manifests per cache, in
     * {@link YoutubeProgressiveDashManifestCreator}, {@link YoutubeOtfDashManifestCreator} and
     * {@link YoutubePostLiveStreamDvrDashManifestCreator}.
     */
    private static final int MAXIMUM_SIZE_CACHED_GENERATED_MANIFESTS_PER_CACHE = 500;

    private final int continueLoadingCheckIntervalBytes;
    private final CacheFactory.Builder cacheDataSourceFactoryBuilder;
    private final DataSource.Factory cachelessDataSourceFactory;
    private final DataSource.Factory nicoCachelessDataSourceFactory;

    public PlayerDataSource(@NonNull final Context context,
                            @NonNull final String userAgent,
                            @NonNull final TransferListener transferListener) {
        continueLoadingCheckIntervalBytes = PlayerHelper.getProgressiveLoadIntervalBytes(context);
        cacheDataSourceFactoryBuilder = new CacheFactory.Builder(context, userAgent,
                transferListener);
        cachelessDataSourceFactory = new DefaultDataSource.Factory(context,
                new DefaultHttpDataSource.Factory().setUserAgent(userAgent)
                        .setDefaultRequestProperties(Map.of("Referer", "https://www.bilibili.com")))
                .setTransferListener(transferListener);

        YoutubeProgressiveDashManifestCreator.getCache().setMaximumSize(
                MAXIMUM_SIZE_CACHED_GENERATED_MANIFESTS_PER_CACHE);
        YoutubeOtfDashManifestCreator.getCache().setMaximumSize(
                MAXIMUM_SIZE_CACHED_GENERATED_MANIFESTS_PER_CACHE);
        YoutubePostLiveStreamDvrDashManifestCreator.getCache().setMaximumSize(
                MAXIMUM_SIZE_CACHED_GENERATED_MANIFESTS_PER_CACHE);
        nicoCachelessDataSourceFactory = new DefaultDataSource
                .Factory(context, new DefaultHttpDataSource.Factory().setUserAgent(userAgent)
                .setDefaultRequestProperties(Map.of("Referer", "https://www.nicovideo.jp/",
                        "Origin", "https://www.nicovideo.jp",
                        "X-Frontend-ID", "6",
                        "X-Frontend-Version", "0",
                        "X-Niconico-Language", "en-us"
                )))
                .setTransferListener(transferListener);
    }
    public String getNicoUrl(String url){
        final Map<String, List<String>> headers = new HashMap<>();
        headers.put("Content-Type", Collections.singletonList("application/json"));
        DownloaderImpl downloader = DownloaderImpl.getInstance();
        Response response;
        try {
            response = downloader.get(String.valueOf(url), null, NiconicoService.LOCALE); // NiconicoService.LOCALE = Localization.fromLocalizationCode("ja-JP")
            final Document page = Jsoup.parse(response.responseBody());
            JsonObject watch = JsonParser.object().from(
                    page.getElementById("js-initial-watch-data").attr("data-api-data"));
            final JsonObject session
                    = watch.getObject("media").getObject("delivery").getObject("movie");

            final JsonObject encryption = watch.getObject("media").getObject("delivery").getObject("encryption");
            final String s = NiconicoDMCPayloadBuilder.buildJSON(session.getObject("session"), encryption);
            response = downloader.post("https://api.dmc.nico/api/sessions?_format=json", headers, s.getBytes(StandardCharsets.UTF_8), NiconicoService.LOCALE);
            final JsonObject content = JsonParser.object().from(response.responseBody());
            final String contentURL = content.getObject("data").getObject("session")
                    .getString("content_uri");
            return String.valueOf(Uri.parse(contentURL));
        } catch (ReCaptchaException | JsonParserException | IOException e) {
            e.printStackTrace();
        }
        return null;
    }

    public MediaSource.Factory getNicoDataSource() {
        DataSource.Factory newFactory = new ResolvingDataSource.Factory(nicoCachelessDataSourceFactory, dataSpec -> {
            return dataSpec.withUri(Uri.parse(getNicoUrl(String.valueOf(dataSpec.uri))));
        });
        return new ProgressiveMediaSource.Factory(newFactory)
                .setContinueLoadingCheckIntervalBytes(continueLoadingCheckIntervalBytes);
    }

    public HlsMediaSource.Factory getNicoHlsMediaSourceFactory() {
        DataSource.Factory newFactory = new ResolvingDataSource.Factory(cacheDataSourceFactoryBuilder.build(), dataSpec -> {
            return dataSpec.withAdditionalHeaders(Map.of("Referer", "https://www.nicovideo.jp/",
                    "Origin", "https://www.nicovideo.jp",
                    "X-Frontend-ID", "6",
                    "X-Frontend-Version", "0",
                    "X-Niconico-Language", "en-us"
            ));
        });
        return new HlsMediaSource.Factory(newFactory);
    }
    public SsMediaSource.Factory getLiveSsMediaSourceFactory() {
        return getSSMediaSourceFactory().setLivePresentationDelayMs(LIVE_STREAM_EDGE_GAP_MILLIS);
    }

    public HlsMediaSource.Factory getLiveHlsMediaSourceFactory() {
        return new HlsMediaSource.Factory(cachelessDataSourceFactory)
                .setAllowChunklessPreparation(true)
                .setPlaylistTrackerFactory((dataSourceFactory, loadErrorHandlingPolicy,
                                            playlistParserFactory) ->
                        new DefaultHlsPlaylistTracker(dataSourceFactory, loadErrorHandlingPolicy,
                                playlistParserFactory,
                                PLAYLIST_STUCK_TARGET_DURATION_COEFFICIENT));
    }

    public DashMediaSource.Factory getLiveDashMediaSourceFactory() {
        return new DashMediaSource.Factory(
                getDefaultDashChunkSourceFactory(cachelessDataSourceFactory),
                cachelessDataSourceFactory);
    }

    public HlsMediaSource.Factory getHlsMediaSourceFactory(
            @Nullable final HlsPlaylistParserFactory hlsPlaylistParserFactory) {
        final HlsMediaSource.Factory factory = new HlsMediaSource.Factory(
                cacheDataSourceFactoryBuilder.build());
        if (hlsPlaylistParserFactory != null) {
            factory.setPlaylistParserFactory(hlsPlaylistParserFactory);
        }
        return factory;
    }

    public DashMediaSource.Factory getDashMediaSourceFactory() {
        return new DashMediaSource.Factory(
                getDefaultDashChunkSourceFactory(cacheDataSourceFactoryBuilder.build()),
                cacheDataSourceFactoryBuilder.build());
    }

    public ProgressiveMediaSource.Factory getProgressiveMediaSourceFactory() {
        return new ProgressiveMediaSource.Factory(cachelessDataSourceFactory)
                .setContinueLoadingCheckIntervalBytes(continueLoadingCheckIntervalBytes);
    }

    public SsMediaSource.Factory getSSMediaSourceFactory() {
        return new SsMediaSource.Factory(
                new DefaultSsChunkSource.Factory(cachelessDataSourceFactory),
                cachelessDataSourceFactory);
    }

    public SingleSampleMediaSource.Factory getSingleSampleMediaSourceFactory() {
        return new SingleSampleMediaSource.Factory(cacheDataSourceFactoryBuilder.build());
    }

    public DashMediaSource.Factory getYoutubeDashMediaSourceFactory() {
        cacheDataSourceFactoryBuilder.setUpstreamDataSourceFactory(
                getYoutubeHttpDataSourceFactory(true, true));
        return new DashMediaSource.Factory(
                getDefaultDashChunkSourceFactory(cacheDataSourceFactoryBuilder.build()),
                cacheDataSourceFactoryBuilder.build());
    }

    public HlsMediaSource.Factory getYoutubeHlsMediaSourceFactory() {
        cacheDataSourceFactoryBuilder.setUpstreamDataSourceFactory(
                getYoutubeHttpDataSourceFactory(false, false));
        return new HlsMediaSource.Factory(cacheDataSourceFactoryBuilder.build());
    }

    public ProgressiveMediaSource.Factory getYoutubeProgressiveMediaSourceFactory() {
        cacheDataSourceFactoryBuilder.setUpstreamDataSourceFactory(
                getYoutubeHttpDataSourceFactory(false, true));
        return new ProgressiveMediaSource.Factory(cacheDataSourceFactoryBuilder.build())
                .setContinueLoadingCheckIntervalBytes(continueLoadingCheckIntervalBytes);
    }

    @NonNull
    private DefaultDashChunkSource.Factory getDefaultDashChunkSourceFactory(
            final DataSource.Factory dataSourceFactory) {
        return new DefaultDashChunkSource.Factory(dataSourceFactory);
    }

    @NonNull
    private YoutubeHttpDataSource.Factory getYoutubeHttpDataSourceFactory(
            final boolean rangeParameterEnabled,
            final boolean rnParameterEnabled) {
        return new YoutubeHttpDataSource.Factory()
                .setRangeParameterEnabled(rangeParameterEnabled)
                .setRnParameterEnabled(rnParameterEnabled);
    }
}
