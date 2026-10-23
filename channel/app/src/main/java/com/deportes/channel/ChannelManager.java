package com.deportes.channel;

import android.content.ContentUris;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.net.Uri;
import androidx.tvprovider.media.tv.Channel;
import androidx.tvprovider.media.tv.ChannelLogoUtils;
import androidx.tvprovider.media.tv.PreviewProgram;
import androidx.tvprovider.media.tv.TvContractCompat;

import java.util.List;

public class ChannelManager {

    private static final String PREFS = "deportes_prefs";
    private static final String KEY_ID = "channel_id";
    private static final String CHANNEL_NAME = "Deportes";

    public static void publishAsync(final Context context) {
        new Thread(new Runnable() {
            @Override public void run() {
                try { publish(context); } catch (Exception e) { e.printStackTrace(); }
            }
        }).start();
    }

    private static void publish(Context context) throws Exception {
        long channelId = getOrCreateChannel(context);
        if (channelId <= 0) return;
        List<DataFetcher.Event> events = DataFetcher.fetchAll();
        try {
            context.getContentResolver().delete(
                TvContractCompat.buildPreviewProgramsUriForChannel(channelId), null, null);
        } catch (Exception e) { /* ignore */ }
        int weight = events.size();
        for (DataFetcher.Event e : events) {
            insertProgram(context, channelId, e, weight--);
        }
    }

    private static long getOrCreateChannel(Context context) {
        SharedPreferences sp = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        long id = sp.getLong(KEY_ID, -1);
        if (id > 0) return id;
        Channel channel = new Channel.Builder()
            .setType(TvContractCompat.Channels.TYPE_PREVIEW)
            .setDisplayName(CHANNEL_NAME)
            .setAppLinkIntentUri(buildIntentUri(context))
            .build();
        Uri uri = context.getContentResolver().insert(
            TvContractCompat.Channels.CONTENT_URI, channel.toContentValues());
        if (uri == null) return -1;
        id = ContentUris.parseId(uri);
        ChannelLogoUtils.storeChannelLogo(context, id, buildLogo());
        sp.edit().putLong(KEY_ID, id).apply();
        return id;
    }

    public static void requestBrowsable(Context context) {
        long id = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong(KEY_ID, -1);
        if (id > 0) {
            try { TvContractCompat.requestChannelBrowsable(context, id); } catch (Exception e) { /* ignore */ }
        }
    }

    private static Uri buildIntentUri(Context context) {
        Intent intent = new Intent(context, MainActivity.class);
        return Uri.parse(intent.toUri(Intent.URI_INTENT_SCHEME));
    }

    private static Bitmap buildLogo() {
        int size = 256;
        Bitmap bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bmp);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(Color.parseColor("#1E88E5"));
        c.drawCircle(size / 2f, size / 2f, size / 2f, p);
        p.setColor(Color.WHITE);
        p.setTextSize(100);
        p.setTextAlign(Paint.Align.CENTER);
        c.drawText("DEP", size / 2f, size / 2f + 34, p);
        return bmp;
    }

    private static void insertProgram(Context context, long channelId, DataFetcher.Event e, int weight) {
        PreviewProgram prog = new PreviewProgram.Builder()
            .setChannelId(channelId)
            .setType(TvContractCompat.PreviewPrograms.TYPE_CLIP)
            .setTitle(e.name)
            .setDescription(e.comp + " · " + e.time)
            .setIntentUri(buildIntentUri(context))
            .setInternalProviderId(e.iso)
            .setWeight(weight)
            .build();
        try {
            context.getContentResolver().insert(
                TvContractCompat.PreviewPrograms.CONTENT_URI, prog.toContentValues());
        } catch (Exception ex) { /* ignore */ }
    }
}
