package org.levimc.launcher.core.curseforge;

import org.levimc.launcher.BuildConfig;
import com.google.gson.Gson;

import org.levimc.launcher.core.curseforge.models.ContentSearchResponse;
import org.levimc.launcher.core.curseforge.models.ModFilesResponse;
import org.levimc.launcher.core.curseforge.models.StringResponse;

import java.io.IOException;
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

    private static IOException httpError(Response response) {
        int code = response.code();
        if (code == 401 || code == 403) {
            String reason = API_KEY == null || API_KEY.isEmpty()
                    ? "no CurseForge API key in this build (set curseforge.api_key in local.properties)"
                    : "CurseForge rejected the API key";
            return new IOException(reason + " (HTTP " + code + ")");
        }
        return new IOException("Unexpected code " + response);
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
                    callback.onError(httpError(response));
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
                    callback.onError(httpError(response));
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
                    callback.onError(httpError(response));
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
}
