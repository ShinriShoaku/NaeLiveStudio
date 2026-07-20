package ame.project.nlstudio.OBS;

import android.content.Context;
import androidx.annotation.OptIn;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.datasource.cache.CacheDataSource;
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor;
import androidx.media3.datasource.cache.SimpleCache;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.datasource.DefaultDataSource;

import java.io.File;

@OptIn(markerClass = UnstableApi.class)
public class VideoDiskCacheManager {
    private static final String TAG = "VideoDiskCache";
    private static VideoDiskCacheManager instance;
    private SimpleCache simpleCache;
    private static final long MAX_CACHE_SIZE = 500L * 1024 * 1024; // 500MB

    private VideoDiskCacheManager(Context context) {
        File cacheDir = new File(context.getCacheDir(), "video_cache");
        if (!cacheDir.exists()) cacheDir.mkdirs();
        LeastRecentlyUsedCacheEvictor evictor = new LeastRecentlyUsedCacheEvictor(MAX_CACHE_SIZE);
        simpleCache = new SimpleCache(cacheDir, evictor, new androidx.media3.database.StandaloneDatabaseProvider(context));
    }

    public static synchronized VideoDiskCacheManager getInstance(Context context) {
        if (instance == null) {
            instance = new VideoDiskCacheManager(context.getApplicationContext());
        }
        return instance;
    }

    public SimpleCache getCache() {
        return simpleCache;
    }

    public CacheDataSource.Factory createCacheDataSourceFactory(Context context) {
        DefaultHttpDataSource.Factory httpDataSourceFactory = new DefaultHttpDataSource.Factory()
                .setAllowCrossProtocolRedirects(true);
        DefaultDataSource.Factory defaultDataSourceFactory = new DefaultDataSource.Factory(context, httpDataSourceFactory);
        
        return new CacheDataSource.Factory()
                .setCache(simpleCache)
                .setUpstreamDataSourceFactory(defaultDataSourceFactory)
                .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR);
    }
}
