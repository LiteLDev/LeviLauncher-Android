package org.levimc.launcher.core.curseforge;

import org.levimc.launcher.BuildConfig;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import org.levimc.launcher.core.curseforge.models.ContentSearchResponse;
import org.levimc.launcher.core.curseforge.models.ModFilesResponse;
import org.levimc.launcher.core.curseforge.models.StringResponse;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

public class CurseForgeClient {
    private static final String TAG = "CurseForgeClient";
    private static final String BASE_URL = "https://api.curseforge.com";
    private static final String API_KEY = BuildConfig.CURSEFORGE_API_KEY;

    public static final int GAME_ID_MINECRAFT = 78022;

    public static final String SORT_POPULARITY = "2";
    public static final String SORT_LAST_UPDATED = "3";
    public static final String SORT_NAME = "4";
    public static final String SORT_TOTAL_DOWNLOADS = "6";
    
    private final OkHttpClient client;
    private final Gson gson;

    private static CurseForgeClient instance;

    private CurseForgeClient() {
        this.client = new OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .build();
        this.gson = new Gson();
    }

    public static synchronized CurseForgeClient getInstance() {
        if (instance == null) {
            instance = new CurseForgeClient();
        }
        return instance;
    }

    public interface CurseForgeCallback<T> {
        void onSuccess(T result);
        void onError(Throwable t);
    }

    public void searchContent(String query, int classId, String version, int index, int pageSize, String sortField, String sortOrder, CurseForgeCallback<ContentSearchResponse> callback) {
        HttpUrl.Builder urlBuilder = HttpUrl.parse(BASE_URL + "/v1/mods/search").newBuilder();
        urlBuilder.addQueryParameter("gameId", String.valueOf(GAME_ID_MINECRAFT));
        urlBuilder.addQueryParameter("sortField", sortField != null ? sortField : SORT_POPULARITY);
        urlBuilder.addQueryParameter("sortOrder", sortOrder != null ? sortOrder : "desc");
        urlBuilder.addQueryParameter("index", String.valueOf(index));
        urlBuilder.addQueryParameter("pageSize", String.valueOf(pageSize));
        
        if (query != null && !query.isEmpty()) {
            urlBuilder.addQueryParameter("searchFilter", query);
        }
        if (classId > 0) {
            urlBuilder.addQueryParameter("classId", String.valueOf(classId));
        }
        if (version != null && !version.isEmpty() && !"All".equals(version)) {
            urlBuilder.addQueryParameter("gameVersion", version);
        }

        Request request = new Request.Builder()
                .url(urlBuilder.build())
                .addHeader("x-api-key", API_KEY)
                .addHeader("Accept", "application/json")
                .build();

        client.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                callback.onError(e);
            }

            @Override
            public void onResponse(Call call, Response response) {
                if (!response.isSuccessful()) {
                    callback.onError(new IOException("Unexpected code " + response));
                    return;
                }
                
                try {
                    String json = response.body().string();
                    ContentSearchResponse contentResponse = gson.fromJson(json, ContentSearchResponse.class);
                    callback.onSuccess(contentResponse);
                } catch (Exception e) {
                    callback.onError(e);
                }
            }
        });
    }

    public void getContentDescription(int contentId, CurseForgeCallback<String> callback) {
        String url = BASE_URL + "/v1/mods/" + contentId + "/description";
        Request request = new Request.Builder()
                .url(url)
                .addHeader("x-api-key", API_KEY)
                .addHeader("Accept", "application/json")
                .build();

        client.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                callback.onError(e);
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                if (!response.isSuccessful()) {
                    callback.onError(new IOException("Unexpected code " + response));
                    return;
                }

                try {
                    String json = response.body().string();
                    StringResponse stringResponse = gson.fromJson(json, StringResponse.class);
                    callback.onSuccess(stringResponse.data);
                } catch (Exception e) {
                    callback.onError(e);
                }
            }
        });
    }

    public void getModFiles(int modId, int index, int pageSize, CurseForgeCallback<ModFilesResponse> callback) {
        HttpUrl.Builder urlBuilder = HttpUrl.parse(BASE_URL + "/v1/mods/" + modId + "/files").newBuilder();
        urlBuilder.addQueryParameter("index", String.valueOf(index));
        urlBuilder.addQueryParameter("pageSize", String.valueOf(pageSize));

        Request request = new Request.Builder()
                .url(urlBuilder.build())
                .addHeader("x-api-key", API_KEY)
                .addHeader("Accept", "application/json")
                .build();

        client.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                callback.onError(e);
            }

            @Override
            public void onResponse(Call call, Response response) {
                if (!response.isSuccessful()) {
                    callback.onError(new IOException("Unexpected code " + response));
                    return;
                }

                try {
                    String json = response.body().string();
                    ModFilesResponse filesResponse = gson.fromJson(json, ModFilesResponse.class);
                    callback.onSuccess(filesResponse);
                } catch (Exception e) {
                    callback.onError(e);
                }
            }
        });
    }

    /** The game's versions (newest first), for the search's game version filter. */
    public void getGameVersions(CurseForgeCallback<List<String>> callback) {
        Request request = new Request.Builder()
                .url(BASE_URL + "/v2/games/" + GAME_ID_MINECRAFT + "/versions")
                .addHeader("x-api-key", API_KEY)
                .addHeader("Accept", "application/json")
                .build();

        client.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                callback.onError(e);
            }

            @Override
            public void onResponse(Call call, Response response) {
                if (!response.isSuccessful()) {
                    callback.onError(new IOException("Unexpected code " + response));
                    return;
                }
                try {
                    String json = response.body().string();
                    callback.onSuccess(parseGameVersions(json));
                } catch (Exception e) {
                    callback.onError(e);
                }
            }
        });
    }

    // v2 shape: {"data":[{"type":N,"versions":[{"id":..,"slug":..,"name":"1.21.0"}, ...]}, ...]}
    static List<String> parseGameVersions(String json) {
        Set<String> names = new LinkedHashSet<>();
        JsonObject root = JsonParser.parseString(json).getAsJsonObject();
        JsonArray data = root.has("data") && root.get("data").isJsonArray() ? root.getAsJsonArray("data") : new JsonArray();
        for (JsonElement group : data) {
            if (!group.isJsonObject() || !group.getAsJsonObject().has("versions")) continue;
            JsonElement versions = group.getAsJsonObject().get("versions");
            if (!versions.isJsonArray()) continue;
            for (JsonElement v : versions.getAsJsonArray()) {
                String name = null;
                if (v.isJsonPrimitive()) {
                    name = v.getAsString();
                } else if (v.isJsonObject() && v.getAsJsonObject().has("name")) {
                    name = v.getAsJsonObject().get("name").getAsString();
                }
                if (name != null && !name.trim().isEmpty() && Character.isDigit(name.trim().charAt(0))) {
                    names.add(name.trim());
                }
            }
        }
        List<String> out = new ArrayList<>(names);
        out.sort((a, b) -> compareVersions(b, a));
        return out;
    }

    private static int compareVersions(String a, String b) {
        String[] pa = a.split("[^0-9]+");
        String[] pb = b.split("[^0-9]+");
        for (int i = 0; i < Math.max(pa.length, pb.length); i++) {
            long x = i < pa.length && !pa[i].isEmpty() ? parseLong(pa[i]) : 0;
            long y = i < pb.length && !pb[i].isEmpty() ? parseLong(pb[i]) : 0;
            if (x != y) return Long.compare(x, y);
        }
        return a.compareTo(b);
    }

    private static long parseLong(String s) {
        try {
            return Long.parseLong(s);
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
