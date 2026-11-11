package org.levimc.launcher.ui.activities;

import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AlertDialog;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.switchmaterial.SwitchMaterial;
import com.google.android.material.tabs.TabLayout;

import org.json.JSONArray;
import org.json.JSONObject;
import org.levimc.launcher.R;
import org.levimc.launcher.core.content.leveldb.WorldDatabase;
import org.levimc.launcher.core.content.nbt.NativeNbt;
import org.levimc.launcher.core.content.nbt.NbtDocument;
import org.levimc.launcher.core.content.nbt.NbtTag;
import org.levimc.launcher.core.content.world.ChunkKey;
import org.levimc.launcher.core.content.world.MapPalette;
import org.levimc.launcher.core.content.world.MapSettings;
import org.levimc.launcher.core.content.world.WorldChunk;
import org.levimc.launcher.core.content.world.WorldLocator;
import org.levimc.launcher.core.content.world.WorldMapRenderer;
import org.levimc.launcher.core.content.world.WorldOperations;
import org.levimc.launcher.core.content.world.WorldRecords;
import org.levimc.launcher.core.content.world.WorldSession;
import org.levimc.launcher.ui.world.NbtTreePanel;
import org.levimc.launcher.ui.world.WorldMapView;
import org.levimc.launcher.ui.world.WorldUi;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Consumer;

public final class WorldEditorActivity extends BaseActivity implements NbtTreePanel.Host {
    public static final String EXTRA_WORLD_PATH = "world_path", EXTRA_WORLD_NAME = "world_name";
    private static final String[] LAYERS = {
        "Satellite",
        "Cave",
        "Slime chunks",
        "Heightmap",
        "Biomes",
        "Foliage color",
        "X-ray",
        "Light"
    };

    private static final class State {
        WorldSession session;
        WorldMapRenderer renderer;
        MapSettings settings = new MapSettings();
        WorldRecords.Document document;
        byte[] key, raw, pendingExport;
        Bitmap pendingImage;
        boolean metadata, standalone, draftDirty;
        int tab;
        int importMode;
        double centerX, centerZ;
        float scale = 5;
        Integer x1, z1, x2, z2;
        int y1 = -64, y2 = 319;
        List<WorldRecords.Marker> players = new ArrayList<>(), bookmarks = new ArrayList<>();
    }

    private State state;
    private ExecutorService executor;
    private Future<?> task;
    private volatile boolean active;
    private boolean busy, saving, initialized;
    private boolean switchingTab;
    private int job;
    private LinearLayout root, mapPanel, nbtPanel, recordsPanel, worldNbtButton, localPlayerButton;
    private FrameLayout pages, editorPanel, blocker;
    private TextView status, progressLabel, recordSummary;
    private MaterialButton saveButton, layerButton, dimensionButton, yButton;
    private TabLayout tabs;
    private WorldMapView map;
    private RecyclerView records;
    private EditText recordSearch;
    private final List<byte[]> recordKeys = new ArrayList<>();
    private final RecordAdapter recordAdapter = new RecordAdapter();
    private byte[] nextPage;
    private NbtTreePanel tree;
    private final android.os.Handler handler =
            new android.os.Handler(android.os.Looper.getMainLooper());
    private Runnable searchRunnable;
    private ActivityResultLauncher<String> exportFile;
    private ActivityResultLauncher<String[]> importFile;

    @Override
    protected boolean shouldSkipNavBar() {
        return true;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Object retained = getLastCustomNonConfigurationInstance();
        if (retained instanceof State) state = (State) retained;
        else {
            String path = getIntent().getStringExtra(EXTRA_WORLD_PATH);
            if (path == null) {
                finish();
                return;
            }
            state = new State();
            state.session = new WorldSession(new File(path));
            state.renderer = new WorldMapRenderer(state.session);
            loadPreferences();
        }
        executor =
                Executors.newSingleThreadExecutor(
                        r -> {
                            Thread t = new Thread(r, "LeviWorldWorkspace");
                            t.setPriority(Thread.MIN_PRIORITY);
                            return t;
                        });
        exportFile =
                registerForActivityResult(
                        new ActivityResultContracts.CreateDocument("application/octet-stream"),
                        uri -> {
                            if (uri == null) {
                                clearExport();
                                return;
                            }
                            byte[] bytes = state.pendingExport;
                            Bitmap image = state.pendingImage;
                            state.pendingExport = null;
                            state.pendingImage = null;
                            if (bytes == null && image == null) return;
                            work(
                                    "Exporting",
                                    () -> {
                                        try (OutputStream out =
                                                getContentResolver().openOutputStream(uri)) {
                                            if (out == null)
                                                throw new IOException(
                                                        "Cannot open the selected destination");
                                            if (image != null) {
                                                if (!image.compress(
                                                        Bitmap.CompressFormat.PNG, 100, out))
                                                    throw new IOException(
                                                            "Cannot encode screenshot");
                                            } else out.write(bytes);
                                            out.flush();
                                        } finally {
                                            if (image != null) image.recycle();
                                        }
                                        return true;
                                    },
                                    v -> {
                                        if (state.standalone) {
                                            state.draftDirty = false;
                                            updatePending();
                                        }
                                        toast("Exported");
                                    });
                        });
        importFile =
                registerForActivityResult(
                        new ActivityResultContracts.OpenDocument(),
                        uri -> {
                            if (uri == null) return;
                            int mode = state.importMode;
                            work(
                                    "Reading file",
                                    () -> {
                                        try (InputStream in =
                                                getContentResolver().openInputStream(uri)) {
                                            if (in == null)
                                                throw new IOException("Cannot open file");
                                            return readBounded(in);
                                        }
                                    },
                                    bytes -> {
                                        if (mode == 1) importStructure(bytes);
                                        else if (mode == 2) openNbtFile(bytes);
                                        else if (mode == 3) replaceValue(bytes);
                                    });
                        });
        buildUi();
    }

    private void buildUi() {
        root = WorldUi.column(this, 0);
        root.setBackgroundColor(WorldUi.color(this, R.color.background));
        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(4), 0, dp(8), 0);
        header.addView(
                WorldUi.backButton(this, this::leave),
                new LinearLayout.LayoutParams(dp(44), dp(44)));
        String name = getIntent().getStringExtra(EXTRA_WORLD_NAME);
        TextView title = WorldUi.text(this, name == null ? "World" : name, 17, false);
        title.setSingleLine(true);
        title.setEllipsize(android.text.TextUtils.TruncateAt.END);
        title.setPadding(dp(4), 0, dp(12), 0);
        header.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        tabs = new TabLayout(this);
        tabs.setTabMode(TabLayout.MODE_FIXED);
        tabs.setSelectedTabIndicatorColor(WorldUi.accent(this));
        tabs.setTabTextColors(WorldUi.color(this, R.color.text_secondary), WorldUi.accent(this));
        tabs.setBackgroundColor(android.graphics.Color.TRANSPARENT);
        for (String tab : new String[] {"Map", "NBT", "Structures"}) {
            TextView label = WorldUi.text(this, tab, 13, true);
            label.setGravity(Gravity.CENTER);
            label.setTextColor(
                    new ColorStateList(
                            new int[][] {new int[] {android.R.attr.state_selected}, new int[] {}},
                            new int[] {
                                WorldUi.accent(this), WorldUi.color(this, R.color.text_secondary)
                            }));
            tabs.addTab(tabs.newTab().setCustomView(label));
        }
        header.addView(tabs, new LinearLayout.LayoutParams(dp(268), -1));
        saveButton = WorldUi.button(this, "Save", this::save);
        header.addView(saveButton, new LinearLayout.LayoutParams(dp(68), dp(42)));
        root.addView(header, new LinearLayout.LayoutParams(-1, dp(50)));
        FrameLayout area = new FrameLayout(this);
        pages = new FrameLayout(this);
        area.addView(pages, new FrameLayout.LayoutParams(-1, -1));
        root.addView(area, new LinearLayout.LayoutParams(-1, 0, 1));
        buildMap();
        buildNbt();
        buildRecords();
        blocker = new FrameLayout(this);
        blocker.setClickable(true);
        blocker.setBackgroundColor(
                WorldUi.translucent(WorldUi.color(this, R.color.background), 175));
        LinearLayout progress = WorldUi.column(this, 12);
        progress.setGravity(Gravity.CENTER);
        progress.addView(new ProgressBar(this));
        progressLabel = WorldUi.text(this, "", 12, true);
        progressLabel.setPadding(0, dp(12), 0, dp(4));
        progress.addView(progressLabel);
        progress.addView(
                WorldUi.button(
                        this,
                        "Cancel",
                        () -> {
                            if (!saving && task != null) {
                                task.cancel(true);
                                busy = false;
                                job++;
                                blocker.setVisibility(View.GONE);
                                updatePending();
                            }
                        }));
        blocker.addView(progress, new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER));
        area.addView(blocker, new FrameLayout.LayoutParams(-1, -1));
        blocker.setVisibility(View.GONE);
        status = WorldUi.text(this, "", 11, true);
        status.setPadding(dp(16), 0, dp(16), 0);
        status.setGravity(Gravity.CENTER_VERTICAL);
        status.setSingleLine(true);
        status.setEllipsize(android.text.TextUtils.TruncateAt.END);
        root.addView(status, new LinearLayout.LayoutParams(-1, dp(24)));
        setContentView(root);
        tabs.addOnTabSelectedListener(
                new TabLayout.OnTabSelectedListener() {
                    public void onTabSelected(TabLayout.Tab tab) {
                        handleTab(tab.getPosition());
                    }

                    public void onTabUnselected(TabLayout.Tab tab) {}

                    public void onTabReselected(TabLayout.Tab tab) {}
                });
        state.tab = Math.min(2, state.tab);
        tabs.getTabAt(state.tab).select();
        showTab(state.tab);
    }

    private void buildMap() {
        mapPanel = WorldUi.column(this, 0);
        LinearLayout toolbar = new LinearLayout(this);
        toolbar.setGravity(Gravity.CENTER_VERTICAL);
        toolbar.setPadding(dp(8), 0, dp(8), 0);
        layerButton =
                WorldUi.button(
                        this, LAYERS[state.settings.layer.ordinal()] + " ▾", this::chooseLayer);
        dimensionButton =
                WorldUi.button(
                        this,
                        WorldRecords.dimensionName(state.settings.dimension) + " ▾",
                        this::chooseDimension);
        yButton = WorldUi.button(this, "Y " + state.settings.y, this::chooseY);
        toolbar.addView(layerButton);
        toolbar.addView(dimensionButton);
        toolbar.addView(yButton);
        mapPanel.addView(toolbar, new LinearLayout.LayoutParams(-1, dp(42)));
        FrameLayout mapArea = new FrameLayout(this);
        map = new WorldMapView(this, state.session, state.renderer);
        map.configure(state.settings);
        map.viewport(state.centerX, state.centerZ, state.scale);
        map.setPlayers(state.players);
        map.setBookmarks(state.bookmarks);
        map.setListener(
                new WorldMapView.Listener() {
                    public void inspect(int x, int z, boolean hold) {
                        WorldEditorActivity.this.inspect(x, z, hold);
                    }

                    public void moved(double x, double z, float scale) {
                        state.centerX = x;
                        state.centerZ = z;
                        state.scale = scale;
                        if (!busy) mapStatus();
                    }

                    public void error(String message) {
                        if (!busy) status.setText(message);
                    }
                });
        mapArea.addView(map, new FrameLayout.LayoutParams(-1, -1));
        LinearLayout controls = new LinearLayout(this);
        controls.setGravity(Gravity.BOTTOM);
        ImageView tools = WorldUi.iconButton(this, R.drawable.ic_add, "Tools", this::showTools);
        tools.setBackground(WorldUi.ripple(this, WorldUi.color(this, R.color.surface), 12));
        tools.setImageTintList(ColorStateList.valueOf(WorldUi.accent(this)));
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(dp(44), dp(44));
        tp.setMargins(0, 0, dp(8), 0);
        controls.addView(tools, tp);
        LinearLayout zoom = WorldUi.column(this, 0);
        zoom.setBackground(WorldUi.background(this, WorldUi.color(this, R.color.surface), 12));
        zoom.addView(
                WorldUi.iconButton(
                        this, R.drawable.ic_world_zoom_in, "Zoom in", () -> map.zoom(1.5f)),
                new LinearLayout.LayoutParams(dp(44), dp(44)));
        zoom.addView(
                WorldUi.iconButton(
                        this, R.drawable.ic_world_zoom_out, "Zoom out", () -> map.zoom(1 / 1.5f)),
                new LinearLayout.LayoutParams(dp(44), dp(44)));
        controls.addView(zoom);
        FrameLayout.LayoutParams cp =
                new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.END);
        cp.setMargins(0, 0, dp(12), dp(12));
        mapArea.addView(controls, cp);
        mapPanel.addView(mapArea, new LinearLayout.LayoutParams(-1, 0, 1));
        pages.addView(mapPanel, new FrameLayout.LayoutParams(-1, -1));
        refreshSelection();
    }

    private void buildRecords() {
        recordsPanel = WorldUi.column(this, 8);
        recordsPanel.setBackground(
                WorldUi.background(this, WorldUi.color(this, R.color.surface), 12));
        LinearLayout toolbar = new LinearLayout(this);
        toolbar.setGravity(Gravity.CENTER_VERTICAL);
        recordSearch = WorldUi.input(this, "Search structures", "", false);
        toolbar.addView(recordSearch, new LinearLayout.LayoutParams(0, dp(38), 1));
        toolbar.addView(
                WorldUi.button(
                        this,
                        "Import",
                        () -> {
                            state.importMode = 1;
                            importFile.launch(new String[] {"*/*"});
                        }));
        recordsPanel.addView(toolbar, new LinearLayout.LayoutParams(-1, dp(48)));
        recordSearch.addTextChangedListener(
                new TextWatcher() {
                    public void beforeTextChanged(CharSequence s, int a, int b, int c) {}

                    public void onTextChanged(CharSequence s, int a, int b, int c) {
                        if (searchRunnable != null) handler.removeCallbacks(searchRunnable);
                        searchRunnable =
                                () -> {
                                    if (active && !busy && state.tab == 2) loadRecords(true);
                                };
                        handler.postDelayed(searchRunnable, 350);
                    }

                    public void afterTextChanged(Editable e) {}
                });
        records = new RecyclerView(this);
        records.setLayoutManager(new LinearLayoutManager(this));
        records.setAdapter(recordAdapter);
        records.addOnScrollListener(
                new RecyclerView.OnScrollListener() {
                    @Override
                    public void onScrolled(RecyclerView view, int dx, int dy) {
                        if (dy > 0 && !view.canScrollVertically(1) && nextPage != null && !busy)
                            loadRecords(false);
                    }
                });
        recordsPanel.addView(records, new LinearLayout.LayoutParams(-1, 0, 1));
        recordSummary = WorldUi.text(this, "", 12, true);
        recordSummary.setGravity(Gravity.CENTER);
        recordSummary.setPadding(dp(12), dp(8), dp(12), dp(8));
        recordsPanel.addView(recordSummary);
        FrameLayout.LayoutParams rp = new FrameLayout.LayoutParams(-1, -1);
        rp.setMargins(dp(8), dp(8), dp(8), dp(8));
        pages.addView(recordsPanel, rp);
    }

    private void buildNbt() {
        nbtPanel = new LinearLayout(this);
        nbtPanel.setPadding(dp(8), dp(8), dp(8), dp(8));
        LinearLayout choices = WorldUi.column(this, 0);
        worldNbtButton = nbtChoice("World NBT", "level.dat", () -> openRecord(null, true));
        localPlayerButton =
                nbtChoice(
                        "~local_player",
                        "",
                        () -> openRecord("~local_player".getBytes(StandardCharsets.UTF_8), false));
        choices.addView(worldNbtButton, new LinearLayout.LayoutParams(-1, dp(72)));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, dp(64));
        lp.topMargin = dp(8);
        choices.addView(localPlayerButton, lp);
        int width =
                Math.max(148, Math.min(184, getResources().getConfiguration().screenWidthDp / 4));
        nbtPanel.addView(choices, new LinearLayout.LayoutParams(dp(width), -1));
        editorPanel = new FrameLayout(this);
        editorPanel.setBackground(
                WorldUi.background(this, WorldUi.color(this, R.color.surface), 12));
        editorPanel.setClipToOutline(true);
        LinearLayout.LayoutParams ep = new LinearLayout.LayoutParams(0, -1, 1);
        ep.setMarginStart(dp(8));
        nbtPanel.addView(editorPanel, ep);
        pages.addView(nbtPanel, new FrameLayout.LayoutParams(-1, -1));
    }

    private LinearLayout nbtChoice(String title, String subtitle, Runnable action) {
        LinearLayout card = WorldUi.column(this, 14);
        card.setGravity(Gravity.CENTER_VERTICAL);
        TextView name = WorldUi.text(this, title, 13, false);
        name.setSingleLine(true);
        card.addView(name);
        if (!subtitle.isEmpty()) {
            TextView detail = WorldUi.text(this, subtitle, 11, true);
            detail.setPadding(0, dp(4), 0, 0);
            card.addView(detail);
        }
        card.setOnClickListener(
                v -> {
                    if (!busy && initialized) action.run();
                });
        card.setFocusable(true);
        return card;
    }

    private void updateNbtChoices() {
        boolean open = state.document != null || state.raw != null;
        boolean local =
                open
                        && !state.standalone
                        && !state.metadata
                        && Arrays.equals(
                                state.key, "~local_player".getBytes(StandardCharsets.UTF_8));
        LinearLayout[] choices = {worldNbtButton, localPlayerButton};
        boolean[] selected = {open && state.metadata, local};
        for (int i = 0; i < choices.length; i++) {
            choices[i].setSelected(selected[i]);
            choices[i].setBackground(
                    WorldUi.ripple(
                            this,
                            selected[i]
                                    ? WorldUi.translucent(WorldUi.accent(this), 25)
                                    : WorldUi.color(this, R.color.surface),
                            12));
            ((TextView) choices[i].getChildAt(0))
                    .setTextColor(
                            selected[i]
                                    ? WorldUi.accent(this)
                                    : WorldUi.color(this, R.color.on_surface));
        }
    }

    private void showTools() {
        if (busy) return;
        String[] names = {
            "Selection",
            "Blocks",
            "Biomes",
            "Structures",
            "Locate",
            "Go to",
            "Map options",
            "Screenshot",
            "Selection image",
            "Advanced NBT",
            "Torch shades",
            "Delete chunks"
        };
        int[] icons = {
            R.drawable.ic_world_selection,
            R.drawable.ic_minecraft_cube,
            R.drawable.ic_world,
            R.drawable.ic_structure,
            R.drawable.ic_search,
            R.drawable.ic_world_location,
            R.drawable.ic_settings,
            R.drawable.ic_photo,
            R.drawable.ic_export,
            R.drawable.ic_edit,
            R.drawable.ic_world_light,
            R.drawable.ic_delete
        };
        Runnable[] actions = {
            this::selectionBounds,
            this::blockTools,
            this::editBiome,
            this::structureTools,
            this::locate,
            this::goMenu,
            this::mapOptions,
            this::screenshot,
            this::selectionImage,
            this::advancedNbt,
            this::torchShades,
            this::deleteChunks
        };
        ScrollView scroll = new ScrollView(this);
        LinearLayout grid = WorldUi.column(this, 12);
        scroll.addView(grid);
        AlertDialog dialog =
                WorldUi.show(
                        this,
                        new MaterialAlertDialogBuilder(this)
                                .setTitle("Tools")
                                .setView(scroll)
                                .setNegativeButton("Close", null));
        int columns = getResources().getConfiguration().screenWidthDp >= 760 ? 4 : 3;
        for (int i = 0; i < names.length; i += columns) {
            LinearLayout row = new LinearLayout(this);
            grid.addView(row, new LinearLayout.LayoutParams(-1, dp(82)));
            for (int j = i; j < Math.min(i + columns, names.length); j++) {
                final int index = j;
                LinearLayout tile = WorldUi.column(this, 8);
                tile.setGravity(Gravity.CENTER);
                tile.setBackground(
                        WorldUi.ripple(this, WorldUi.color(this, R.color.surface_variant), 12));
                ImageView icon = new ImageView(this);
                icon.setImageResource(icons[j]);
                if (icons[j] != R.drawable.ic_minecraft_cube) {
                    icon.setImageTintList(ColorStateList.valueOf(WorldUi.accent(this)));
                }
                tile.addView(icon, new LinearLayout.LayoutParams(dp(24), dp(24)));
                TextView label = WorldUi.text(this, names[j], 11, false);
                label.setGravity(Gravity.CENTER);
                label.setPadding(0, dp(6), 0, 0);
                tile.addView(label);
                tile.setFocusable(true);
                tile.setOnClickListener(
                        v -> {
                            dialog.dismiss();
                            actions[index].run();
                        });
                LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, -1, 1);
                p.setMargins(dp(4), dp(4), dp(4), dp(4));
                row.addView(tile, p);
            }
        }
        if (dialog.getWindow() != null)
            dialog.getWindow()
                    .setLayout(
                            dp(
                                    Math.min(
                                            getResources().getConfiguration().screenWidthDp - 40,
                                            columns * 132 + 40)),
                            -2);
    }

    private void advancedNbt() {
        WorldUi.show(
                this,
                new MaterialAlertDialogBuilder(this)
                        .setTitle("Advanced NBT")
                        .setItems(
                                new String[] {
                                    "Open database key",
                                    "Locator",
                                    "Open NBT file",
                                    "Create record",
                                    "Restore last save",
                                    "Discard changes",
                                    "Reload map"
                                },
                                (d, i) -> {
                                    if (i == 0) openByKey();
                                    else if (i == 1) locate();
                                    else if (i == 2) {
                                        state.importMode = 2;
                                        importFile.launch(new String[] {"*/*"});
                                    } else if (i == 3) createRecord();
                                    else if (i == 4)
                                        confirm(
                                                "Restore last save?",
                                                "Load the previous values for review before"
                                                        + " saving.",
                                                () ->
                                                        work(
                                                                "Reading edit history",
                                                                () -> {
                                                                    state.session
                                                                            .restoreLatestSave();
                                                                    return true;
                                                                },
                                                                v -> changedWorkspace()));
                                    else if (i == 5)
                                        confirm(
                                                "Discard changes?",
                                                "Unsaved edits will be removed.",
                                                () ->
                                                        work(
                                                                "Reloading world",
                                                                () -> {
                                                                    state.session.discard();
                                                                    return true;
                                                                },
                                                                v -> {
                                                                    clearOpenDocument();
                                                                    changedWorkspace();
                                                                    showTab(state.tab);
                                                                }));
                                    else
                                        work(
                                                "Refreshing map",
                                                () -> {
                                                    state.renderer.clear();
                                                    return true;
                                                },
                                                v -> {
                                                    map.refresh();
                                                    configureMap();
                                                });
                                }));
    }

    private void handleTab(int position) {
        if (switchingTab) return;
        if (busy) {
            switchingTab = true;
            tabs.getTabAt(state.tab).select();
            switchingTab = false;
            return;
        }
        if (position != state.tab
                && (state.document != null || state.raw != null)
                && state.draftDirty) {
            if (state.standalone) {
                switchingTab = true;
                tabs.getTabAt(state.tab).select();
                switchingTab = false;
                resolveDraft(
                        () -> {
                            clearOpenDocument();
                            tabs.getTabAt(position).select();
                        });
                return;
            }
            work(
                    "Keeping NBT edits",
                    () -> {
                        stageDraft();
                        return true;
                    },
                    v -> {
                        clearOpenDocument();
                        showTab(position);
                    });
            return;
        }
        if (position != state.tab && (state.document != null || state.raw != null))
            clearOpenDocument();
        showTab(position);
    }

    private void clearOpenDocument() {
        state.document = null;
        state.raw = null;
        state.key = null;
        state.metadata = false;
        state.standalone = false;
        state.draftDirty = false;
        tree = null;
    }

    private void showTab(int tab) {
        state.tab = tab;
        if (tabs.getSelectedTabPosition() != tab) {
            switchingTab = true;
            tabs.getTabAt(tab).select();
            switchingTab = false;
        }
        mapPanel.setVisibility(tab == 0 ? View.VISIBLE : View.GONE);
        nbtPanel.setVisibility(tab == 1 ? View.VISIBLE : View.GONE);
        recordsPanel.setVisibility(tab == 2 ? View.VISIBLE : View.GONE);
        status.setVisibility(tab == 0 ? View.VISIBLE : View.GONE);
        if (tab == 0) {
            if (active && initialized) map.start();
            mapStatus();
        } else map.stop();
        if (tab == 1) renderEditor();
        else if (tab == 2 && active && initialized) loadRecords(true);
        refreshSelection();
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (state == null) return;
        active = true;
        state.session.resume();
        work(
                "Opening saved world",
                () -> {
                    state.session.initialize();
                    List<WorldRecords.Marker> players = WorldRecords.players(state.session);
                    return players;
                },
                players -> {
                    initialized = true;
                    state.players = players;
                    map.setPlayers(players);
                    loadBookmarks();
                    updatePending();
                    if (state.tab == 0) {
                        map.start();
                        mapStatus();
                    } else if (state.tab == 1) renderEditor();
                    else if (state.tab == 2) loadRecords(true);
                });
    }

    @Override
    protected void onStop() {
        active = false;
        job++;
        if (searchRunnable != null) handler.removeCallbacks(searchRunnable);
        if (state != null) {
            state.centerX = map.centerX();
            state.centerZ = map.centerZ();
            state.scale = map.zoom();
            savePreferences();
            map.stop();
            state.session.suspend();
        }
        if (task != null && !saving) task.cancel(true);
        busy = false;
        if (blocker != null) blocker.setVisibility(View.GONE);
        if (executor != null && !executor.isShutdown())
            executor.execute(() -> state.session.releaseDatabase());
        super.onStop();
    }

    @Override
    public Object onRetainCustomNonConfigurationInstance() {
        return state;
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        if (executor != null) {
            if (!isChangingConfigurations() && state != null)
                executor.execute(
                        () -> {
                            state.session.close();
                            state.renderer.clear();
                            clearExport();
                        });
            executor.shutdown();
        }
        super.onDestroy();
    }

    private <T> void work(String message, Callable<T> operation, Consumer<T> accept) {
        if (busy || executor == null || executor.isShutdown()) return;
        busy = true;
        int token = ++job;
        if (status != null) status.setText(message);
        if (progressLabel != null) progressLabel.setText(message);
        updatePending();
        if (blocker != null) blocker.setVisibility(View.VISIBLE);
        task =
                executor.submit(
                        () -> {
                            try {
                                T result = operation.call();
                                runOnUiThread(
                                        () -> {
                                            if (token != job || !active || isDestroyed()) return;
                                            busy = false;
                                            saving = false;
                                            blocker.setVisibility(View.GONE);
                                            updatePending();
                                            accept.accept(result);
                                        });
                            } catch (Exception | LinkageError ex) {
                                runOnUiThread(
                                        () -> {
                                            if (token != job || !active || isDestroyed()) return;
                                            busy = false;
                                            saving = false;
                                            blocker.setVisibility(View.GONE);
                                            error(
                                                    ex.getMessage() == null
                                                            ? ex.getClass().getSimpleName()
                                                            : ex.getMessage());
                                            updatePending();
                                        });
                            }
                        });
    }

    private void updatePending() {
        if (state == null || saveButton == null) return;
        boolean changed = state.session.pendingCount() > 0 || state.draftDirty;
        saveButton.setEnabled(changed && !busy);
        saveButton.setAlpha(changed ? 1f : .45f);
        saveButton.setText(state.standalone && state.draftDirty ? "Export" : "Save");
    }

    private void save() {
        if (busy) return;
        if (state.standalone) {
            exportDocument();
            return;
        }
        saving = true;
        work(
                "Saving world edits",
                () -> {
                    stageDraft();
                    return state.session.save();
                },
                file -> {
                    changedWorkspace();
                    toast(file == null ? "No changes" : "World saved");
                });
    }

    private void stageDraft() throws IOException {
        if (!state.draftDirty) return;
        if (state.standalone)
            throw new IOException("Export this standalone NBT file before saving world edits");
        byte[] bytes = state.document != null ? state.document.bytes() : state.raw;
        if (state.metadata) {
            NbtDocument doc = NativeNbt.decode(bytes, 0, NativeNbt.LEVEL_DAT);
            state.session.stageMetadata(doc);
        } else state.session.stage(state.key, bytes);
        state.draftDirty = false;
    }

    private void changedWorkspace() {
        map.configure(state.settings);
        if (state.tab == 0 && active) map.start();
        updatePending();
        refreshSelection();
        mapStatus();
    }

    public void changed() {
        state.draftDirty = true;
        updatePending();
    }

    @Override
    public void error(String message) {
        status.setText(message == null ? "Unable to complete operation" : message);
        toast(message);
    }

    private void toast(String message) {
        Toast.makeText(
                        this,
                        message == null ? "Unable to complete operation" : message,
                        Toast.LENGTH_LONG)
                .show();
    }

    private void mapStatus() {
        if (status == null || state == null || busy) return;
        status.setText(String.format(Locale.ROOT, "X %.0f  Z %.0f", state.centerX, state.centerZ));
    }

    private void chooseLayer() {
        WorldUi.show(
                this,
                new MaterialAlertDialogBuilder(this)
                        .setTitle("Map layer")
                        .setSingleChoiceItems(
                                LAYERS,
                                state.settings.layer.ordinal(),
                                (d, i) -> {
                                    state.settings.layer = MapSettings.Layer.values()[i];
                                    if (i == 2) {
                                        state.settings.dimension = 0;
                                        dimensionButton.setText("Overworld ▾");
                                    }
                                    layerButton.setText(LAYERS[i] + " ▾");
                                    d.dismiss();
                                    configureMap();
                                }));
    }

    private void chooseDimension() {
        WorldUi.show(
                this,
                new MaterialAlertDialogBuilder(this)
                        .setTitle("Dimension")
                        .setSingleChoiceItems(
                                new String[] {"Overworld", "Nether", "The End"},
                                state.settings.dimension,
                                (d, i) -> {
                                    state.settings.dimension = i;
                                    state.settings.lowerY = i == 0 ? -64 : 0;
                                    state.settings.upperY = i == 0 ? 319 : i == 1 ? 127 : 255;
                                    state.settings.y =
                                            Math.max(
                                                    state.settings.lowerY,
                                                    Math.min(
                                                            state.settings.upperY,
                                                            state.settings.y));
                                    yButton.setText("Y " + state.settings.y);
                                    dimensionButton.setText(WorldRecords.dimensionName(i) + " ▾");
                                    d.dismiss();
                                    configureMap();
                                    refreshSelection();
                                }));
    }

    private void configureMap() {
        map.configure(state.settings);
        map.setPlayers(state.players);
        mapStatus();
        if (state.tab == 0 && active) map.start();
    }

    private void chooseY() {
        fields(
                "Vertical range",
                new String[] {"Layer Y", "Minimum Y", "Maximum Y"},
                new String[] {
                    "" + state.settings.y, "" + state.settings.lowerY, "" + state.settings.upperY
                },
                values -> {
                    int y = parse(values[0]), min = parse(values[1]), max = parse(values[2]);
                    if (min > max || min < -128 || max > 511 || y < min || y > max)
                        throw new IOException("Use an ordered Y range between -128 and 511");
                    state.settings.y = y;
                    state.settings.lowerY = min;
                    state.settings.upperY = max;
                    yButton.setText("Y " + y);
                    configureMap();
                });
    }

    private void mapOptions() {
        LinearLayout form = WorldUi.column(this, 16);
        ScrollView scroll = new ScrollView(this);
        scroll.addView(form);
        MapSettings edit = state.settings.copy();
        toggle(form, "Chunk grid", edit.grid, v -> edit.grid = v);
        toggle(form, "Coordinates and scale", edit.coordinates, v -> edit.coordinates = v);
        toggle(form, "Player markers", edit.players, v -> edit.players = v);
        toggle(form, "Entity markers", edit.entities, v -> edit.entities = v);
        toggle(form, "Block entity markers", edit.blockEntities, v -> edit.blockEntities = v);
        toggle(form, "Show foliage", edit.leaves, v -> edit.leaves = v);
        toggle(form, "Show water", edit.water, v -> edit.water = v);
        toggle(form, "Terrain shading", edit.shading, v -> edit.shading = v);
        toggle(form, "Use exact Y slice", edit.slice, v -> edit.slice = v);
        toggle(form, "Slime overlay (Overworld)", edit.slimeOverlay, v -> edit.slimeOverlay = v);
        EditText entities = labelInput(form, "Entity filter (comma separated)", edit.entityFilter),
                blockEntities = labelInput(form, "Block entity filter", edit.blockEntityFilter),
                xray = labelInput(form, "X-ray blocks", edit.xray),
                brightness =
                        labelInput(form, "Brightness (0.25–2)", Float.toString(edit.brightness));
        AlertDialog d =
                WorldUi.show(
                        this,
                        new MaterialAlertDialogBuilder(this)
                                .setTitle("Map options")
                                .setView(scroll)
                                .setPositiveButton("Apply", null)
                                .setNegativeButton("Cancel", null));
        d.getButton(AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(
                        v -> {
                            try {
                                edit.brightness = Float.parseFloat(brightness.getText().toString());
                                if (!Float.isFinite(edit.brightness)
                                        || edit.brightness < .25
                                        || edit.brightness > 2)
                                    throw new IllegalArgumentException();
                                edit.entityFilter = entities.getText().toString();
                                edit.blockEntityFilter = blockEntities.getText().toString();
                                edit.xray = xray.getText().toString();
                                state.settings = edit;
                                configureMap();
                                d.dismiss();
                            } catch (IllegalArgumentException ex) {
                                brightness.setError("Use a value from 0.25 to 2");
                            }
                        });
    }

    private void toggle(
            LinearLayout parent, String label, boolean value, Consumer<Boolean> accept) {
        SwitchMaterial toggle = new SwitchMaterial(this);
        toggle.setText(label);
        toggle.setTextSize(13);
        toggle.setTextColor(WorldUi.color(this, R.color.on_surface));
        toggle.setChecked(value);
        toggle.setThumbTintList(
                new ColorStateList(
                        new int[][] {new int[] {android.R.attr.state_checked}, new int[] {}},
                        new int[] {WorldUi.accent(this), WorldUi.color(this, R.color.outline)}));
        toggle.setTrackTintList(
                new ColorStateList(
                        new int[][] {new int[] {android.R.attr.state_checked}, new int[] {}},
                        new int[] {
                            WorldUi.translucent(WorldUi.accent(this), 95),
                            WorldUi.translucent(WorldUi.color(this, R.color.outline), 65)
                        }));
        toggle.setOnCheckedChangeListener((b, c) -> accept.accept(c));
        parent.addView(toggle, new LinearLayout.LayoutParams(-1, dp(44)));
    }

    private void goMenu() {
        WorldUi.show(
                this,
                new MaterialAlertDialogBuilder(this)
                        .setTitle("Go to")
                        .setItems(
                                new String[] {
                                    "World spawn",
                                    "Player",
                                    "Coordinates",
                                    "Bookmarks",
                                    "Add bookmark"
                                },
                                (d, i) -> {
                                    if (i == 0) goSpawn();
                                    else if (i == 1) goPlayer();
                                    else if (i == 2)
                                        fields(
                                                "Coordinates",
                                                new String[] {"X", "Z"},
                                                new String[] {
                                                    "" + (int) state.centerX,
                                                    "" + (int) state.centerZ
                                                },
                                                v -> map.center(parse(v[0]), parse(v[1])));
                                    else if (i == 3) bookmarks();
                                    else
                                        addBookmark(
                                                (int) state.centerX,
                                                state.settings.y,
                                                (int) state.centerZ);
                                }));
    }

    private void goSpawn() {
        work(
                "Reading spawn",
                () -> {
                    NbtTag root = state.session.metadata().root;
                    return new int[] {
                        number(root, "SpawnX", 0),
                        number(root, "SpawnY", 64),
                        number(root, "SpawnZ", 0),
                        number(root, "SpawnDimension", 0)
                    };
                },
                p -> {
                    state.settings.dimension = p[3];
                    state.settings.y = p[1];
                    dimensionButton.setText(WorldRecords.dimensionName(p[3]) + " ▾");
                    yButton.setText("Y " + p[1]);
                    map.center(p[0], p[2]);
                    configureMap();
                });
    }

    private void goPlayer() {
        work(
                "Reading players",
                () -> WorldRecords.players(state.session),
                players -> {
                    state.players = players;
                    map.setPlayers(players);
                    if (players.isEmpty()) {
                        error("No saved players were found");
                        return;
                    }
                    String[] labels =
                            players.stream()
                                    .map(
                                            p ->
                                                    p.label + " · " + (int) p.x + ", " + (int) p.y
                                                            + ", " + (int) p.z)
                                    .toArray(String[]::new);
                    WorldUi.show(
                            this,
                            new MaterialAlertDialogBuilder(this)
                                    .setTitle("Players")
                                    .setItems(labels, (d, i) -> goMarker(players.get(i))));
                });
    }

    private void goMarker(WorldRecords.Marker marker) {
        state.settings.dimension = marker.dimension;
        state.settings.y = (int) marker.y;
        dimensionButton.setText(WorldRecords.dimensionName(marker.dimension) + " ▾");
        yButton.setText("Y " + state.settings.y);
        map.center(marker.x, marker.z);
        configureMap();
        tabs.getTabAt(0).select();
    }

    private void inspect(int x, int z, boolean hold) {
        if (busy || !initialized) return;
        work(
                "Inspecting block",
                () -> {
                    WorldChunk chunk =
                            state.renderer.chunk(
                                    Math.floorDiv(x, 16),
                                    Math.floorDiv(z, 16),
                                    state.settings.dimension);
                    int y =
                            state.settings.slice
                                    ? state.settings.y
                                    : chunk.top(x & 15, z & 15, state.settings.leaves);
                    NbtTag block = chunk.blockAt(x & 15, y, z & 15);
                    return new Object[] {
                        chunk,
                        y,
                        block,
                        NativeNbt.toSnbt(block),
                        WorldRecords.chunkMarkers(state.session, chunk.x, chunk.z, state.settings)
                    };
                },
                info -> {
                    WorldChunk chunk = (WorldChunk) info[0];
                    int y = (int) info[1];
                    NbtTag block = (NbtTag) info[2];
                    String detail =
                            x
                                    + ", "
                                    + y
                                    + ", "
                                    + z
                                    + "\n"
                                    + WorldChunk.name(block)
                                    + "\n"
                                    + MapPalette.biomeName(chunk.biome(x & 15, y, z & 15))
                                    + "\nChunk "
                                    + chunk.x
                                    + ", "
                                    + chunk.z;
                    if (!chunk.exists) detail += "\nThis area has not been generated.";
                    String[] actions = {
                        "Block states",
                        "Set selection start",
                        "Set selection end",
                        "Bookmark",
                        "Block entities NBT",
                        "Entities NBT",
                        "Teleport saved player here",
                        "Move world spawn here",
                        "Chunk records"
                    };
                    WorldUi.show(
                            this,
                            new MaterialAlertDialogBuilder(this)
                                    .setTitle("Inspect block")
                                    .setMessage(detail)
                                    .setPositiveButton(
                                            hold ? "Select" : "Actions",
                                            (d, w) ->
                                                    WorldUi.show(
                                                            this,
                                                            new MaterialAlertDialogBuilder(this)
                                                                    .setTitle(
                                                                            x + ", " + y + ", " + z)
                                                                    .setItems(
                                                                            actions,
                                                                            (dialog, i) -> {
                                                                                if (i == 0)
                                                                                    editBlock(
                                                                                            x,
                                                                                            y,
                                                                                            z,
                                                                                            (String)
                                                                                                    info[
                                                                                                            3]);
                                                                                else if (i == 1) {
                                                                                    state.x1 = x;
                                                                                    state.z1 = z;
                                                                                    state.y1 = y;
                                                                                    state.x2 = null;
                                                                                    state.z2 = null;
                                                                                    refreshSelection();
                                                                                } else if (i == 2) {
                                                                                    if (state.x1
                                                                                            == null) {
                                                                                        state.x1 =
                                                                                                x;
                                                                                        state.z1 =
                                                                                                z;
                                                                                    }
                                                                                    state.x2 = x;
                                                                                    state.z2 = z;
                                                                                    state.y2 = y;
                                                                                    refreshSelection();
                                                                                } else if (i == 3)
                                                                                    addBookmark(
                                                                                            x, y,
                                                                                            z);
                                                                                else if (i == 4)
                                                                                    openRecord(
                                                                                            ChunkKey
                                                                                                    .of(
                                                                                                            chunk.x,
                                                                                                            chunk.z,
                                                                                                            state.settings
                                                                                                                    .dimension,
                                                                                                            0x31),
                                                                                            false);
                                                                                else if (i == 5)
                                                                                    entityRecords(
                                                                                            chunk.x,
                                                                                            chunk.z);
                                                                                else if (i == 6)
                                                                                    teleport(
                                                                                            x,
                                                                                            y + 1,
                                                                                            z);
                                                                                else if (i == 7)
                                                                                    moveSpawn(
                                                                                            x,
                                                                                            y + 1,
                                                                                            z);
                                                                                else
                                                                                    chunkRecords(
                                                                                            chunk.x,
                                                                                            chunk.z);
                                                                            })))
                                    .setNegativeButton("Close", null));
                });
    }

    private void editBlock(int x, int y, int z, String snbt) {
        fields(
                "Block palette state",
                new String[] {"Block compound (SNBT)"},
                new String[] {snbt},
                values -> {
                    WorldOperations.Region region =
                            new WorldOperations.Region(x, y, z, x, y, z, state.settings.dimension);
                    work(
                            "Editing block",
                            () ->
                                    new WorldOperations(state.session)
                                            .blocks(
                                                    region,
                                                    null,
                                                    WorldOperations.parseBlock(values[0]),
                                                    0,
                                                    this::operationProgress),
                            count -> changedWorkspace());
                });
    }

    private void entityRecords(int cx, int cz) {
        work(
                "Reading entity records",
                () -> {
                    MapSettings all = state.settings.copy();
                    all.entities = true;
                    all.blockEntities = false;
                    all.entityFilter = "";
                    return WorldRecords.chunkMarkers(state.session, cx, cz, all);
                },
                markers -> {
                    if (markers.isEmpty()) {
                        error("No saved entities in this chunk");
                        return;
                    }
                    WorldUi.show(
                            this,
                            new MaterialAlertDialogBuilder(this)
                                    .setTitle("Entities")
                                    .setItems(
                                            markers.stream()
                                                    .map(
                                                            m ->
                                                                    m.label + " · " + (int) m.x
                                                                            + ", " + (int) m.y
                                                                            + ", " + (int) m.z)
                                                    .toArray(String[]::new),
                                            (d, i) -> openRecord(markers.get(i).key, false)));
                });
    }

    private void chunkRecords(int cx, int cz) {
        work(
                "Reading chunk records",
                () -> {
                    List<byte[]> keys = new ArrayList<>();
                    byte[] next = null;
                    do {
                        WorldDatabase.Page page =
                                state.session.scan(ChunkKey.prefix(cx, cz), next, 256, "");
                        for (byte[] key : page.keys) {
                            ChunkKey c = ChunkKey.parse(key);
                            if (c != null && c.dimension == state.settings.dimension) keys.add(key);
                        }
                        next = page.next;
                    } while (next != null);
                    return keys;
                },
                keys -> {
                    if (keys.isEmpty()) {
                        error("No records in this chunk");
                        return;
                    }
                    WorldUi.show(
                            this,
                            new MaterialAlertDialogBuilder(this)
                                    .setTitle("Chunk records")
                                    .setItems(
                                            keys.stream()
                                                    .map(WorldRecords::label)
                                                    .toArray(String[]::new),
                                            (d, i) -> openRecord(keys.get(i), false)));
                });
    }

    private void teleport(int x, int y, int z) {
        work(
                "Reading players",
                () -> WorldRecords.players(state.session),
                players -> {
                    if (players.isEmpty()) {
                        error("No saved players");
                        return;
                    }
                    WorldUi.show(
                            this,
                            new MaterialAlertDialogBuilder(this)
                                    .setTitle("Move saved player")
                                    .setItems(
                                            players.stream()
                                                    .map(p -> p.label)
                                                    .toArray(String[]::new),
                                            (d, i) ->
                                                    confirm(
                                                            "Move player?",
                                                            x
                                                                    + ", "
                                                                    + y
                                                                    + ", "
                                                                    + z
                                                                    + " in "
                                                                    + WorldRecords.dimensionName(
                                                                            state.settings
                                                                                    .dimension),
                                                            () ->
                                                                    work(
                                                                            "Updating player",
                                                                            () -> {
                                                                                WorldRecords.Marker
                                                                                        player =
                                                                                                players
                                                                                                        .get(
                                                                                                                i);
                                                                                WorldRecords
                                                                                                .Document
                                                                                        doc =
                                                                                                WorldRecords
                                                                                                        .decode(
                                                                                                                state
                                                                                                                        .session
                                                                                                                        .get(
                                                                                                                                player.key));
                                                                                NbtTag pos =
                                                                                        doc.root
                                                                                                .getTag(
                                                                                                        "Pos");
                                                                                if (pos == null
                                                                                        || pos.getList()
                                                                                                        .size()
                                                                                                != 3)
                                                                                    throw new IOException(
                                                                                            "Player"
                                                                                                + " position"
                                                                                                + " is unavailable");
                                                                                for (int j = 0;
                                                                                        j < 3;
                                                                                        j++)
                                                                                    pos.getList()
                                                                                            .get(j)
                                                                                            .setValue(
                                                                                                    (float)
                                                                                                            (j
                                                                                                                            == 0
                                                                                                                    ? x
                                                                                                                            + .5
                                                                                                                    : j
                                                                                                                                    == 1
                                                                                                                            ? y
                                                                                                                            : z
                                                                                                                                    + .5));
                                                                                doc.root.putTag(
                                                                                        "DimensionId",
                                                                                        WorldOperations
                                                                                                .intTag(
                                                                                                        "DimensionId",
                                                                                                        state.settings
                                                                                                                .dimension));
                                                                                NbtTag motion =
                                                                                        doc.root
                                                                                                .getTag(
                                                                                                        "Motion");
                                                                                if (motion != null)
                                                                                    for (NbtTag n :
                                                                                            motion
                                                                                                    .getList())
                                                                                        n.setValue(
                                                                                                0f);
                                                                                state.session.stage(
                                                                                        player.key,
                                                                                        doc
                                                                                                .bytes());
                                                                                return true;
                                                                            },
                                                                            v ->
                                                                                    changedWorkspace()))));
                });
    }

    private void moveSpawn(int x, int y, int z) {
        confirm(
                "Move world spawn?",
                x + ", " + y + ", " + z,
                () ->
                        work(
                                "Updating spawn",
                                () -> {
                                    NbtDocument doc = state.session.metadata();
                                    doc.root.putTag("SpawnX", WorldOperations.intTag("SpawnX", x));
                                    doc.root.putTag("SpawnY", WorldOperations.intTag("SpawnY", y));
                                    doc.root.putTag("SpawnZ", WorldOperations.intTag("SpawnZ", z));
                                    doc.root.putTag(
                                            "SpawnDimension",
                                            WorldOperations.intTag(
                                                    "SpawnDimension", state.settings.dimension));
                                    state.session.stageMetadata(doc);
                                    return true;
                                },
                                v -> changedWorkspace()));
    }

    private void loadRecords(boolean reset) {
        if (!initialized || busy || state.tab != 2 || !reset && nextPage == null) return;
        byte[] start = reset ? null : nextPage;
        String query = recordSearch.getText().toString();
        byte[] prefix = WorldRecords.prefix(WorldRecords.Group.STRUCTURES);
        work(
                "Reading structures",
                () -> {
                    List<byte[]> keys = new ArrayList<>();
                    if (reset)
                        for (byte[] key : state.session.stagedKeys(prefix))
                            if (WorldRecords.matches(key, WorldRecords.Group.STRUCTURES)
                                    && new String(key, StandardCharsets.UTF_8)
                                            .toLowerCase(Locale.ROOT)
                                            .contains(query.toLowerCase(Locale.ROOT)))
                                keys.add(key);
                    WorldDatabase.Page page = state.session.scan(prefix, start, 128, query);
                    keys.addAll(Arrays.asList(page.keys));
                    return new Object[] {keys, page.next};
                },
                result -> {
                    if (reset) recordKeys.clear();
                    recordKeys.addAll((List<byte[]>) result[0]);
                    nextPage = (byte[]) result[1];
                    recordAdapter.notifyDataSetChanged();
                    recordSummary.setText(
                            recordKeys.isEmpty() && nextPage == null ? "No saved structures" : "");
                    recordSummary.setVisibility(
                            recordKeys.isEmpty() && nextPage == null ? View.VISIBLE : View.GONE);
                    records.post(
                            () -> {
                                if (state.tab == 2
                                        && !records.canScrollVertically(1)
                                        && nextPage != null
                                        && !busy) loadRecords(false);
                            });
                });
    }

    private void openRecord(byte[] key, boolean metadata) {
        if (busy || !initialized) return;
        if ((state.document != null || state.raw != null)
                && !state.standalone
                && state.metadata == metadata
                && Arrays.equals(state.key, key)) return;
        if (state.draftDirty) {
            resolveDraft(() -> openRecord(key, metadata));
            return;
        }
        work(
                "Opening NBT",
                () -> {
                    byte[] bytes =
                            metadata ? state.session.metadata().encode() : state.session.get(key);
                    if (bytes == null)
                        throw new IOException("This world has no record at that key");
                    WorldRecords.Document document = null;
                    try {
                        document = WorldRecords.decode(bytes);
                    } catch (IOException ignored) {
                    }
                    return new Object[] {bytes, document};
                },
                result -> {
                    state.key = key;
                    state.metadata = metadata;
                    state.standalone = false;
                    state.document = (WorldRecords.Document) result[1];
                    state.raw = state.document == null ? (byte[]) result[0] : null;
                    state.draftDirty = false;
                    showTab(1);
                });
    }

    private void renderEditor() {
        editorPanel.removeAllViews();
        updateNbtChoices();
        if (state.document == null && state.raw == null) {
            tree = null;
            TextView empty = WorldUi.text(this, "Select NBT", 13, true);
            empty.setGravity(Gravity.CENTER);
            editorPanel.addView(empty, new FrameLayout.LayoutParams(-1, -1));
            return;
        }
        LinearLayout body = WorldUi.column(this, 0);
        LinearLayout bar = new LinearLayout(this);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(16), 0, dp(8), 0);
        TextView title =
                WorldUi.text(
                        this,
                        state.metadata
                                ? "World NBT"
                                : state.standalone ? "NBT file" : WorldRecords.label(state.key),
                        14,
                        false);
        title.setSingleLine(true);
        title.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        bar.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        bar.addView(
                WorldUi.iconButton(this, R.drawable.ic_export, "Export NBT", this::exportDocument),
                new LinearLayout.LayoutParams(dp(44), dp(44)));
        body.addView(bar, new LinearLayout.LayoutParams(-1, dp(44)));
        if (state.document != null) {
            tree =
                    new NbtTreePanel(
                            this, state.document.root, this, tag -> state.document.root = tag);
            body.addView(tree, new LinearLayout.LayoutParams(-1, 0, 1));
        } else {
            tree = null;
            TextView hint = WorldUi.text(this, "Binary · " + state.raw.length + " bytes", 13, true);
            hint.setPadding(dp(16), dp(12), dp(16), dp(12));
            body.addView(hint);
            ScrollView scroll = new ScrollView(this);
            TextView preview =
                    WorldUi.text(
                            this,
                            WorldSession.hex(
                                            Arrays.copyOf(
                                                    state.raw, Math.min(state.raw.length, 8192)))
                                    + (state.raw.length > 8192
                                            ? "\n… export to inspect all bytes"
                                            : ""),
                            12,
                            false);
            preview.setTypeface(android.graphics.Typeface.MONOSPACE);
            preview.setTextIsSelectable(true);
            preview.setPadding(dp(16), dp(8), dp(16), dp(8));
            scroll.addView(preview);
            body.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
            LinearLayout tools = new LinearLayout(this);
            tools.addView(
                    WorldUi.button(
                            this,
                            "Edit hex",
                            () -> {
                                if (state.raw.length > 262144) {
                                    error(
                                            "Use Import value for binary records larger than 256"
                                                    + " KiB");
                                    return;
                                }
                                fields(
                                        "Exact hexadecimal value",
                                        new String[] {"Hex bytes"},
                                        new String[] {WorldSession.hex(state.raw)},
                                        v -> {
                                            state.raw =
                                                    v[0].replaceAll("\\s", "").isEmpty()
                                                            ? new byte[0]
                                                            : WorldSession.unhex(v[0]);
                                            changed();
                                            renderEditor();
                                        });
                            }));
            tools.addView(
                    WorldUi.button(
                            this,
                            "Import value",
                            () -> {
                                state.importMode = 3;
                                importFile.launch(new String[] {"*/*"});
                            }));
            body.addView(tools);
        }
        editorPanel.addView(body, new FrameLayout.LayoutParams(-1, -1));
        updatePending();
    }

    private void closeEditor(boolean ask) {
        if (ask && state.draftDirty) {
            resolveDraft(() -> closeEditor(false));
            return;
        }
        clearOpenDocument();
        updatePending();
        showTab(state.tab);
    }

    private void resolveDraft(Runnable next) {
        if (!state.standalone) {
            work(
                    "Keeping NBT edits",
                    () -> {
                        stageDraft();
                        return true;
                    },
                    v -> {
                        updatePending();
                        next.run();
                    });
            return;
        }
        WorldUi.show(
                this,
                new MaterialAlertDialogBuilder(this)
                        .setTitle("Unsaved NBT file")
                        .setMessage("Export your changes before closing?")
                        .setPositiveButton("Export", (d, w) -> exportDocument())
                        .setNeutralButton(
                                "Discard",
                                (d, w) -> {
                                    state.draftDirty = false;
                                    next.run();
                                })
                        .setNegativeButton("Cancel", null));
    }

    @Override
    public void snbt(NbtTag tag, Consumer<NbtTag> accept) {
        work(
                "Formatting SNBT",
                () -> NativeNbt.toSnbt(tag),
                text -> {
                    if (text.length() > 1048576) {
                        error(
                                "This tag is too large for the text editor; navigate its children"
                                        + " or export it");
                        return;
                    }
                    LinearLayout form = WorldUi.column(this, 16);
                    EditText input = WorldUi.input(this, "SNBT", text, true);
                    input.setTypeface(android.graphics.Typeface.MONOSPACE);
                    input.setMinLines(4);
                    input.setMaxLines(12);
                    ScrollView scroll = new ScrollView(this);
                    scroll.addView(input);
                    form.addView(scroll);
                    AlertDialog dialog =
                            WorldUi.show(
                                    this,
                                    new MaterialAlertDialogBuilder(this)
                                            .setTitle("Edit SNBT")
                                            .setView(form)
                                            .setPositiveButton("Apply", null)
                                            .setNegativeButton("Cancel", null));
                    dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                            .setOnClickListener(
                                    v -> {
                                        String value = input.getText().toString();
                                        dialog.dismiss();
                                        work(
                                                "Parsing SNBT",
                                                () -> NativeNbt.fromSnbt(value),
                                                accept);
                                    });
                });
    }

    @Override
    public void search(Runnable operation, Runnable accept) {
        work(
                "Searching NBT tags",
                () -> {
                    operation.run();
                    return true;
                },
                value -> accept.run());
    }

    @Override
    public void copy(NbtTag tag, Consumer<NbtTag> accept) {
        work("Copying tag", () -> WorldOperations.copy(tag), accept);
    }

    private void recordMenu(byte[] key) {
        boolean structure = WorldRecords.matches(key, WorldRecords.Group.STRUCTURES);
        String[] options =
                structure
                        ? new String[] {
                            "Edit NBT",
                            "Export .mcstructure",
                            "Place blocks + block entities",
                            "Import value",
                            "Delete record"
                        }
                        : new String[] {
                            "Edit NBT / binary",
                            "Export value",
                            "Import value",
                            "Copy key as hex",
                            "Delete record"
                        };
        WorldUi.show(
                this,
                new MaterialAlertDialogBuilder(this)
                        .setTitle(WorldRecords.label(key))
                        .setItems(
                                options,
                                (d, i) -> {
                                    if (i == 0) openRecord(key, false);
                                    else if (i == 1)
                                        work(
                                                "Preparing export",
                                                () -> state.session.get(key),
                                                bytes -> {
                                                    if (bytes == null) {
                                                        error("Record was deleted");
                                                        return;
                                                    }
                                                    exportBytes(
                                                            bytes,
                                                            structure
                                                                    ? structureName(key)
                                                                            + ".mcstructure"
                                                                    : "record.nbt");
                                                });
                                    else if (i == 2 && structure) pasteSaved(key);
                                    else if (i == 2 || i == 3 && structure) {
                                        if (state.draftDirty) {
                                            resolveDraft(
                                                    () -> {
                                                        state.key = key;
                                                        state.metadata = false;
                                                        state.standalone = false;
                                                        state.importMode = 3;
                                                        importFile.launch(new String[] {"*/*"});
                                                    });
                                        } else {
                                            state.key = key;
                                            state.metadata = false;
                                            state.standalone = false;
                                            state.importMode = 3;
                                            importFile.launch(new String[] {"*/*"});
                                        }
                                    } else if (i == 3) {
                                        ((android.content.ClipboardManager)
                                                        getSystemService(CLIPBOARD_SERVICE))
                                                .setPrimaryClip(
                                                        android.content.ClipData.newPlainText(
                                                                "World database key",
                                                                WorldSession.hex(key)));
                                        toast("Key copied");
                                    } else
                                        confirm(
                                                "Delete record?",
                                                WorldRecords.label(key) + "\n" + "Save to apply.",
                                                () ->
                                                        work(
                                                                "Deleting record",
                                                                () -> {
                                                                    state.session.stage(key, null);
                                                                    return true;
                                                                },
                                                                v -> {
                                                                    changedWorkspace();
                                                                    loadRecords(true);
                                                                }));
                                }));
    }

    private void openByKey() {
        fields(
                "Open database key",
                new String[] {"UTF-8 key, or hex: followed by hex bytes"},
                new String[] {""},
                v -> {
                    byte[] key =
                            v[0].startsWith("hex:")
                                    ? WorldSession.unhex(v[0].substring(4))
                                    : v[0].getBytes(StandardCharsets.UTF_8);
                    openRecord(key, false);
                });
    }

    private void createRecord() {
        fields(
                "Create NBT record",
                new String[] {"UTF-8 key", "SNBT value"},
                new String[] {"", "{}"},
                v ->
                        work(
                                "Creating record",
                                () -> {
                                    if (v[0].isEmpty())
                                        throw new IOException("Key cannot be empty");
                                    byte[] key = v[0].getBytes(StandardCharsets.UTF_8);
                                    if (state.session.get(key) != null)
                                        throw new IOException("This key already exists");
                                    byte[] bytes =
                                            NativeNbt.encode(
                                                    NativeNbt.fromSnbt(v[1]),
                                                    0,
                                                    NativeNbt.LITTLE_ENDIAN,
                                                    false);
                                    state.session.stage(key, bytes);
                                    return key;
                                },
                                key -> {
                                    changedWorkspace();
                                    openRecord(key, false);
                                }));
    }

    private void exportDocument() {
        if (state.document == null && state.raw == null) return;
        work(
                "Preparing NBT export",
                () -> state.document == null ? state.raw : state.document.bytes(),
                bytes ->
                        exportBytes(
                                bytes,
                                state.metadata
                                        ? "level.dat"
                                        : state.key != null
                                                        && WorldRecords.matches(
                                                                state.key,
                                                                WorldRecords.Group.STRUCTURES)
                                                ? structureName(state.key) + ".mcstructure"
                                                : "edited.nbt"));
    }

    private void exportBytes(byte[] bytes, String filename) {
        state.pendingExport = bytes;
        state.pendingImage = null;
        exportFile.launch(filename.replaceAll("[\\\\/:*?\"<>|]", "_"));
    }

    private void replaceValue(byte[] data) {
        confirm(
                "Replace saved value?",
                "Replaces the entire value. Save to apply.",
                () ->
                        work(
                                "Importing value",
                                () -> {
                                    if (state.metadata) {
                                        NativeNbt.decode(data, 0, NativeNbt.LEVEL_DAT);
                                        state.session.stageMetadata(
                                                NativeNbt.decode(data, 0, NativeNbt.LEVEL_DAT));
                                    } else {
                                        if (WorldRecords.matches(
                                                state.key, WorldRecords.Group.STRUCTURES))
                                            WorldOperations.validateStructure(data);
                                        state.session.stage(state.key, data);
                                    }
                                    return true;
                                },
                                v -> {
                                    state.draftDirty = false;
                                    state.document = null;
                                    state.raw = null;
                                    openRecord(state.key, state.metadata);
                                    changedWorkspace();
                                }));
    }

    private void importStructure(byte[] bytes) {
        work(
                "Reading structure",
                () -> {
                    WorldOperations.validateStructure(bytes);
                    return bytes;
                },
                data ->
                        fields(
                                "Import saved structure",
                                new String[] {"Structure ID"},
                                new String[] {"mystructure:imported"},
                                v ->
                                        work(
                                                "Importing structure",
                                                () -> {
                                                    new WorldOperations(state.session)
                                                            .saveStructure(v[0], data);
                                                    return true;
                                                },
                                                done -> {
                                                    changedWorkspace();
                                                    state.tab = 2;
                                                    tabs.getTabAt(2).select();
                                                    closeEditor(false);
                                                    loadRecords(true);
                                                })));
    }

    private void openNbtFile(byte[] bytes) {
        if (state.draftDirty) {
            resolveDraft(() -> openNbtFile(bytes));
            return;
        }
        WorldUi.show(
                this,
                new MaterialAlertDialogBuilder(this)
                        .setTitle("NBT file format")
                        .setItems(
                                new String[] {
                                    "Bedrock little-endian",
                                    "Bedrock level.dat header",
                                    "Java big-endian"
                                },
                                (d, i) ->
                                        work(
                                                "Opening NBT file",
                                                () -> {
                                                    NbtDocument doc =
                                                            NativeNbt.decode(
                                                                    bytes,
                                                                    0,
                                                                    i == 1
                                                                            ? NativeNbt.LEVEL_DAT
                                                                            : i == 2
                                                                                    ? NativeNbt
                                                                                            .BIG_ENDIAN
                                                                                    : NativeNbt
                                                                                            .LITTLE_ENDIAN);
                                                    if (!doc.compressed
                                                            && doc.bytesRead != bytes.length)
                                                        throw new IOException(
                                                                "File contains trailing data;"
                                                                        + " choose the matching"
                                                                        + " format");
                                                    return new WorldRecords.Document(
                                                            doc.root, false, doc);
                                                },
                                                doc -> {
                                                    state.document = doc;
                                                    state.raw = null;
                                                    state.key = null;
                                                    state.metadata = false;
                                                    state.standalone = true;
                                                    state.draftDirty = false;
                                                    showTab(1);
                                                })));
    }

    private void locate() {
        String[] kinds = {
            "Block name", "Biome ID", "Entity type/name", "Block entity type/name", "Database key"
        };
        WorldUi.show(
                this,
                new MaterialAlertDialogBuilder(this)
                        .setTitle("Advanced locator")
                        .setItems(
                                kinds,
                                (d, i) ->
                                        fields(
                                                "Locate " + kinds[i],
                                                new String[] {
                                                    "Name or ID",
                                                    "Radius in chunks (0–32)",
                                                    "Minimum Y",
                                                    "Maximum Y"
                                                },
                                                new String[] {
                                                    i == 0 ? "diamond_ore" : i == 1 ? "1" : "",
                                                    "8",
                                                    "" + state.settings.lowerY,
                                                    "" + state.settings.upperY
                                                },
                                                v -> {
                                                    int radius = parse(v[1]),
                                                            min = parse(v[2]),
                                                            max = parse(v[3]);
                                                    if (min > max)
                                                        throw new IOException(
                                                                "Minimum Y exceeds maximum Y");
                                                    work(
                                                            "Searching saved chunks",
                                                            () ->
                                                                    new WorldLocator(
                                                                                    state.session,
                                                                                    state.renderer)
                                                                            .search(
                                                                                    WorldLocator
                                                                                            .Kind
                                                                                            .values()[
                                                                                            i],
                                                                                    v[0],
                                                                                    (int)
                                                                                            state.centerX,
                                                                                    (int)
                                                                                            state.centerZ,
                                                                                    radius,
                                                                                    state.settings
                                                                                            .dimension,
                                                                                    min,
                                                                                    max,
                                                                                    this
                                                                                            ::operationProgress),
                                                            results -> {
                                                                if (results.isEmpty()) {
                                                                    error(
                                                                            "No matches in the"
                                                                                + " searched saved"
                                                                                + " data");
                                                                    return;
                                                                }
                                                                WorldUi.show(
                                                                        this,
                                                                        new MaterialAlertDialogBuilder(
                                                                                        this)
                                                                                .setTitle(
                                                                                        results
                                                                                                        .size()
                                                                                                + " matches"
                                                                                                + (results
                                                                                                                        .size()
                                                                                                                >= 512
                                                                                                        ? " (limit)"
                                                                                                        : ""))
                                                                                .setItems(
                                                                                        results
                                                                                                .stream()
                                                                                                .map(
                                                                                                        Object
                                                                                                                ::toString)
                                                                                                .toArray(
                                                                                                        String
                                                                                                                        []
                                                                                                                ::new),
                                                                                        (dialog,
                                                                                                n) -> {
                                                                                            WorldLocator
                                                                                                            .Result
                                                                                                    result =
                                                                                                            results
                                                                                                                    .get(
                                                                                                                            n);
                                                                                            if (result.key
                                                                                                    != null)
                                                                                                openRecord(
                                                                                                        result.key,
                                                                                                        false);
                                                                                            else {
                                                                                                map
                                                                                                        .center(
                                                                                                                result.x,
                                                                                                                result.z);
                                                                                                state.settings
                                                                                                                .y =
                                                                                                        result.y;
                                                                                                yButton
                                                                                                        .setText(
                                                                                                                "Y "
                                                                                                                        + result.y);
                                                                                                configureMap();
                                                                                            }
                                                                                        }));
                                                            });
                                                })));
    }

    private boolean operationProgress(int current, int total) {
        if (Thread.currentThread().isInterrupted() || !active) return false;
        if (current % 8 == 0) {
            int token = job;
            runOnUiThread(
                    () -> {
                        if (active && token == job)
                            status.setText(
                                    total > 0
                                            ? "Processing " + current + " / " + total
                                            : "Scanning database page " + current);
                    });
        }
        return true;
    }

    private void selectionBounds() {
        fields(
                "Selection bounds (inclusive)",
                new String[] {"From X", "From Y", "From Z", "To X", "To Y", "To Z"},
                new String[] {
                    "" + (state.x1 == null ? (int) state.centerX : state.x1),
                    "" + state.y1,
                    "" + (state.z1 == null ? (int) state.centerZ : state.z1),
                    "" + (state.x2 == null ? (int) state.centerX : state.x2),
                    "" + state.y2,
                    "" + (state.z2 == null ? (int) state.centerZ : state.z2)
                },
                v -> {
                    WorldOperations.Region region =
                            new WorldOperations.Region(
                                    parse(v[0]),
                                    parse(v[1]),
                                    parse(v[2]),
                                    parse(v[3]),
                                    parse(v[4]),
                                    parse(v[5]),
                                    state.settings.dimension);
                    region.validateBounds();
                    state.x1 = region.x1;
                    state.y1 = region.y1;
                    state.z1 = region.z1;
                    state.x2 = region.x2;
                    state.y2 = region.y2;
                    state.z2 = region.z2;
                    refreshSelection();
                });
    }

    private WorldOperations.Region selection() throws IOException {
        if (state.x1 == null || state.z1 == null || state.x2 == null || state.z2 == null)
            throw new IOException(
                    "Set both selection corners on the map or enter selection bounds");
        WorldOperations.Region region =
                new WorldOperations.Region(
                        state.x1,
                        state.y1,
                        state.z1,
                        state.x2,
                        state.y2,
                        state.z2,
                        state.settings.dimension);
        region.validateBounds();
        return region;
    }

    private void refreshSelection() {
        if (map != null) map.selection(state.x1, state.z1, state.x2, state.z2);
    }

    private void blockTools() {
        WorldUi.show(
                this,
                new MaterialAlertDialogBuilder(this)
                        .setTitle("Block operations")
                        .setItems(
                                new String[] {
                                    "Fill foreground",
                                    "Replace foreground",
                                    "Fill background",
                                    "Replace background"
                                },
                                (d, i) ->
                                        fields(
                                                "Block edit",
                                                i % 2 == 0
                                                        ? new String[] {
                                                            "Replacement name or full palette SNBT"
                                                        }
                                                        : new String[] {
                                                            "Find names (comma separated)",
                                                            "Replacement name or full palette SNBT"
                                                        },
                                                i % 2 == 0
                                                        ? new String[] {"minecraft:stone"}
                                                        : new String[] {"stone", "minecraft:glass"},
                                                v -> {
                                                    WorldOperations.Region region = selection();
                                                    String find = i % 2 == 0 ? null : v[0],
                                                            block = v[v.length - 1];
                                                    confirm(
                                                            "Apply block edit?",
                                                            region.volume()
                                                                    + " blocks in the selected"
                                                                    + " area. Save to apply.",
                                                            () ->
                                                                    work(
                                                                            "Editing selected"
                                                                                    + " blocks",
                                                                            () ->
                                                                                    new WorldOperations(
                                                                                                    state.session)
                                                                                            .blocks(
                                                                                                    region,
                                                                                                    find,
                                                                                                    WorldOperations
                                                                                                            .parseBlock(
                                                                                                                    block),
                                                                                                    i
                                                                                                            / 2,
                                                                                                    this
                                                                                                            ::operationProgress),
                                                                            count -> {
                                                                                changedWorkspace();
                                                                                toast(
                                                                                        count
                                                                                                + " blocks"
                                                                                                + " changed");
                                                                            }));
                                                })));
    }

    private void editBiome() {
        fields(
                "Change biome",
                new String[] {"Numeric Bedrock biome ID"},
                new String[] {"1"},
                v -> {
                    WorldOperations.Region region = selection();
                    int biome = parse(v[0]);
                    work(
                            "Editing biomes",
                            () ->
                                    new WorldOperations(state.session)
                                            .biome(region, biome, this::operationProgress),
                            count -> {
                                changedWorkspace();
                                toast(count + " chunks updated");
                            });
                });
    }

    private void structureTools() {
        WorldUi.show(
                this,
                new MaterialAlertDialogBuilder(this)
                        .setTitle("Structures")
                        .setItems(
                                new String[] {
                                    "Export selection as .mcstructure",
                                    "Save selection in this world",
                                    "Place a saved structure",
                                    "Import .mcstructure"
                                },
                                (d, i) -> {
                                    if (i == 2) {
                                        state.tab = 2;
                                        tabs.getTabAt(2).select();
                                        closeEditor(false);
                                        loadRecords(true);
                                        return;
                                    }
                                    if (i == 3) {
                                        state.importMode = 1;
                                        importFile.launch(new String[] {"*/*"});
                                        return;
                                    }
                                    try {
                                        WorldOperations.Region region = selection();
                                        if (i == 0)
                                            work(
                                                    "Extracting structure",
                                                    () ->
                                                            new WorldOperations(state.session)
                                                                    .exportStructure(
                                                                            region,
                                                                            this
                                                                                    ::operationProgress),
                                                    bytes ->
                                                            exportBytes(
                                                                    bytes,
                                                                    "selection.mcstructure"));
                                        else
                                            fields(
                                                    "Save structure",
                                                    new String[] {"Structure ID"},
                                                    new String[] {"mystructure:selection"},
                                                    v ->
                                                            work(
                                                                    "Saving selection",
                                                                    () -> {
                                                                        byte[] bytes =
                                                                                new WorldOperations(
                                                                                                state.session)
                                                                                        .exportStructure(
                                                                                                region,
                                                                                                this
                                                                                                        ::operationProgress);
                                                                        new WorldOperations(
                                                                                        state.session)
                                                                                .saveStructure(
                                                                                        v[0],
                                                                                        bytes);
                                                                        return true;
                                                                    },
                                                                    result -> changedWorkspace()));
                                    } catch (IOException ex) {
                                        error(ex.getMessage());
                                    }
                                }));
    }

    private void pasteSaved(byte[] key) {
        fields(
                "Place blocks + block entities",
                new String[] {"Origin X", "Origin Y", "Origin Z"},
                new String[] {
                    "" + (int) state.centerX, "" + state.settings.y, "" + (int) state.centerZ
                },
                v -> {
                    int x = parse(v[0]), y = parse(v[1]), z = parse(v[2]);
                    confirm(
                            "Place saved structure?",
                            "Replaces blocks and block entities at the selected origin.",
                            () ->
                                    work(
                                            "Placing structure",
                                            () ->
                                                    new WorldOperations(state.session)
                                                            .pasteStructure(
                                                                    state.session.get(key),
                                                                    x,
                                                                    y,
                                                                    z,
                                                                    state.settings.dimension,
                                                                    this::operationProgress),
                                            count -> {
                                                changedWorkspace();
                                                toast(count + " block layers placed");
                                            }));
                });
    }

    private void torchShades() {
        try {
            WorldOperations.Region region = selection();
            confirm(
                    "Add glass shades?",
                    "Adds glass above selected torches where space is empty.",
                    () ->
                            work(
                                    "Adding torch shades",
                                    () ->
                                            new WorldOperations(state.session)
                                                    .shadeTorches(region, this::operationProgress),
                                    count -> {
                                        changedWorkspace();
                                        toast(count + " shades added");
                                    }));
        } catch (IOException ex) {
            error(ex.getMessage());
        }
    }

    private void deleteChunks() {
        try {
            WorldOperations.Region region = selection();
            int x1 = Math.floorDiv(region.x1, 16) * 16,
                    z1 = Math.floorDiv(region.z1, 16) * 16,
                    x2 = Math.floorDiv(region.x2, 16) * 16 + 15,
                    z2 = Math.floorDiv(region.z2, 16) * 16 + 15;
            confirm(
                    "Delete chunks?",
                    "Deletes chunks from "
                            + x1
                            + ", "
                            + z1
                            + " to "
                            + x2
                            + ", "
                            + z2
                            + " at all heights, including entities. Save to apply.",
                    () ->
                            work(
                                    "Deleting chunks",
                                    () ->
                                            new WorldOperations(state.session)
                                                    .deleteChunks(region, this::operationProgress),
                                    count -> {
                                        changedWorkspace();
                                        toast(count + " chunks marked for deletion");
                                    }));
        } catch (IOException ex) {
            error(ex.getMessage());
        }
    }

    private void selectionImage() {
        try {
            WorldOperations.Region region = selection();
            MapSettings settings = state.settings.copy();
            work(
                    "Rendering selection image",
                    () -> state.renderer.regionImage(region, settings),
                    image -> {
                        state.pendingImage = image;
                        state.pendingExport = null;
                        exportFile.launch("world-selection.png");
                    });
        } catch (IOException ex) {
            error(ex.getMessage());
        }
    }

    private void screenshot() {
        if (state.tab != 0) tabs.getTabAt(0).select();
        state.pendingImage = map.screenshot();
        state.pendingExport = null;
        exportFile.launch("world-map.png");
    }

    private void addBookmark(int x, int y, int z) {
        fields(
                "Add bookmark",
                new String[] {"Name"},
                new String[] {x + ", " + z},
                v -> {
                    state.bookmarks.add(
                            new WorldRecords.Marker(
                                    v[0],
                                    x,
                                    y,
                                    z,
                                    state.settings.dimension,
                                    WorldRecords.Marker.BOOKMARK,
                                    null));
                    saveBookmarks();
                    map.setBookmarks(state.bookmarks);
                });
    }

    private void bookmarks() {
        if (state.bookmarks.isEmpty()) {
            toast("No bookmarks yet");
            return;
        }
        WorldUi.show(
                this,
                new MaterialAlertDialogBuilder(this)
                        .setTitle("Bookmarks")
                        .setItems(
                                state.bookmarks.stream()
                                        .map(m -> m.label + " · " + (int) m.x + ", " + (int) m.z)
                                        .toArray(String[]::new),
                                (d, i) -> {
                                    WorldRecords.Marker marker = state.bookmarks.get(i);
                                    WorldUi.show(
                                            this,
                                            new MaterialAlertDialogBuilder(this)
                                                    .setTitle(marker.label)
                                                    .setItems(
                                                            new String[] {
                                                                "Go to", "Delete bookmark"
                                                            },
                                                            (dialog, n) -> {
                                                                if (n == 0) goMarker(marker);
                                                                else {
                                                                    state.bookmarks.remove(i);
                                                                    saveBookmarks();
                                                                    map.setBookmarks(
                                                                            state.bookmarks);
                                                                }
                                                            }));
                                }));
    }

    private SharedPreferences preferences() {
        return getSharedPreferences(
                "world_workspace_"
                        + Integer.toHexString(state.session.world.getAbsolutePath().hashCode()),
                MODE_PRIVATE);
    }

    private void loadPreferences() {
        SharedPreferences p = preferences();
        state.centerX = Double.longBitsToDouble(p.getLong("x", 0));
        state.centerZ = Double.longBitsToDouble(p.getLong("z", 0));
        state.scale = p.getFloat("zoom", 5);
        state.settings.dimension = p.getInt("dimension", 0);
        state.settings.y = p.getInt("y", 64);
        state.settings.grid = p.getBoolean("grid", false);
        state.settings.layer =
                MapSettings.Layer.values()[Math.max(0, Math.min(7, p.getInt("layer", 0)))];
        state.settings.lowerY = state.settings.dimension == 0 ? -64 : 0;
        state.settings.upperY =
                state.settings.dimension == 0 ? 319 : state.settings.dimension == 1 ? 127 : 255;
        state.settings.lowerY = p.getInt("lowerY", state.settings.lowerY);
        state.settings.upperY = p.getInt("upperY", state.settings.upperY);
        state.settings.coordinates = p.getBoolean("coordinates", true);
        state.settings.players = p.getBoolean("players", true);
        state.settings.entities = p.getBoolean("entities", false);
        state.settings.blockEntities = p.getBoolean("blockEntities", false);
        state.settings.leaves = p.getBoolean("leaves", true);
        state.settings.water = p.getBoolean("water", true);
        state.settings.shading = p.getBoolean("shading", true);
        state.settings.slice = p.getBoolean("slice", false);
        state.settings.slimeOverlay = p.getBoolean("slimeOverlay", false);
        state.settings.entityFilter = p.getString("entityFilter", "");
        state.settings.blockEntityFilter = p.getString("blockEntityFilter", "");
        state.settings.xray = p.getString("xray", state.settings.xray);
        state.settings.brightness = p.getFloat("brightness", 1f);
    }

    private void savePreferences() {
        preferences()
                .edit()
                .putLong("x", Double.doubleToLongBits(state.centerX))
                .putLong("z", Double.doubleToLongBits(state.centerZ))
                .putFloat("zoom", state.scale)
                .putInt("dimension", state.settings.dimension)
                .putInt("y", state.settings.y)
                .putInt("layer", state.settings.layer.ordinal())
                .putBoolean("grid", state.settings.grid)
                .putInt("lowerY", state.settings.lowerY)
                .putInt("upperY", state.settings.upperY)
                .putBoolean("coordinates", state.settings.coordinates)
                .putBoolean("players", state.settings.players)
                .putBoolean("entities", state.settings.entities)
                .putBoolean("blockEntities", state.settings.blockEntities)
                .putBoolean("leaves", state.settings.leaves)
                .putBoolean("water", state.settings.water)
                .putBoolean("shading", state.settings.shading)
                .putBoolean("slice", state.settings.slice)
                .putBoolean("slimeOverlay", state.settings.slimeOverlay)
                .putString("entityFilter", state.settings.entityFilter)
                .putString("blockEntityFilter", state.settings.blockEntityFilter)
                .putString("xray", state.settings.xray)
                .putFloat("brightness", state.settings.brightness)
                .apply();
    }

    private void loadBookmarks() {
        try {
            JSONArray array = new JSONArray(preferences().getString("bookmarks", "[]"));
            state.bookmarks.clear();
            for (int i = 0; i < array.length(); i++) {
                JSONObject o = array.getJSONObject(i);
                state.bookmarks.add(
                        new WorldRecords.Marker(
                                o.getString("name"),
                                o.getDouble("x"),
                                o.getDouble("y"),
                                o.getDouble("z"),
                                o.getInt("dimension"),
                                WorldRecords.Marker.BOOKMARK,
                                null));
            }
            map.setBookmarks(state.bookmarks);
        } catch (Exception ignored) {
        }
    }

    private void saveBookmarks() {
        try {
            JSONArray array = new JSONArray();
            for (WorldRecords.Marker m : state.bookmarks)
                array.put(
                        new JSONObject()
                                .put("name", m.label)
                                .put("x", m.x)
                                .put("y", m.y)
                                .put("z", m.z)
                                .put("dimension", m.dimension));
            preferences().edit().putString("bookmarks", array.toString()).apply();
        } catch (Exception ex) {
            error(ex.getMessage());
        }
    }

    private interface Values {
        void accept(String[] values) throws Exception;
    }

    private void fields(String title, String[] labels, String[] initial, Values accept) {
        LinearLayout form = WorldUi.column(this, 16);
        List<EditText> inputs = new ArrayList<>();
        for (int i = 0; i < labels.length; i++) {
            EditText input = labelInput(form, labels[i], initial[i]);
            inputs.add(input);
        }
        TextView error = WorldUi.text(this, "", 11, true);
        form.addView(error);
        ScrollView scroll = new ScrollView(this);
        scroll.addView(form);
        AlertDialog dialog =
                WorldUi.show(
                        this,
                        new MaterialAlertDialogBuilder(this)
                                .setTitle(title)
                                .setView(scroll)
                                .setPositiveButton("Apply", null)
                                .setNegativeButton("Cancel", null));
        dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(
                        v -> {
                            try {
                                String[] values = new String[inputs.size()];
                                for (int i = 0; i < values.length; i++)
                                    values[i] = inputs.get(i).getText().toString();
                                accept.accept(values);
                                dialog.dismiss();
                            } catch (Exception ex) {
                                error.setText(
                                        ex.getMessage() == null
                                                ? "Invalid value"
                                                : ex.getMessage());
                            }
                        });
    }

    private EditText labelInput(LinearLayout parent, String label, String value) {
        TextView text = WorldUi.text(this, label, 11, true);
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(-1, -2);
        tp.setMargins(0, dp(6), 0, dp(4));
        parent.addView(text, tp);
        EditText input =
                WorldUi.input(this, label, value, label.contains("SNBT") || label.contains("Hex"));
        input.setMaxLines(label.contains("SNBT") ? 6 : 3);
        parent.addView(input, new LinearLayout.LayoutParams(-1, -2));
        return input;
    }

    private void confirm(String title, String message, Runnable action) {
        WorldUi.show(
                this,
                new MaterialAlertDialogBuilder(this)
                        .setTitle(title)
                        .setMessage(message)
                        .setPositiveButton("Continue", (d, w) -> action.run())
                        .setNegativeButton("Cancel", null));
    }

    private static int parse(String value) throws IOException {
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException ex) {
            throw new IOException("Enter a whole number");
        }
    }

    private static int number(NbtTag tag, String key, int fallback) {
        NbtTag value = tag.getTag(key);
        return value == null ? fallback : value.getInt();
    }

    private int dp(float value) {
        return WorldUi.dp(this, value);
    }

    private static byte[] readBounded(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[16384];
        int n;
        while ((n = in.read(buffer)) != -1) {
            if (Thread.currentThread().isInterrupted()) throw new java.io.InterruptedIOException();
            if ((long) out.size() + n > NativeNbt.MAX_BYTES)
                throw new IOException("File exceeds 64 MiB");
            out.write(buffer, 0, n);
        }
        return out.toByteArray();
    }

    private static String structureName(byte[] key) {
        String value = new String(key, StandardCharsets.UTF_8);
        value = value.startsWith("structuretemplate_") ? value.substring(18) : value;
        return value.replaceAll("[^a-zA-Z0-9._-]", "_");
    }

    private void clearExport() {
        state.pendingExport = null;
        if (state.pendingImage != null) {
            state.pendingImage.recycle();
            state.pendingImage = null;
        }
    }

    @Override
    public void onBackPressed() {
        if (busy) {
            if (!saving && task != null) task.cancel(true);
            return;
        }
        if (state.tab == 1 && (state.document != null || state.raw != null)) {
            if (tree != null && tree.up()) return;
            closeEditor(true);
        } else leave();
    }

    private void leave() {
        if (busy) return;
        if (state.draftDirty || state.session.pendingCount() > 0) {
            WorldUi.show(
                    this,
                    new MaterialAlertDialogBuilder(this)
                            .setTitle("Unsaved world edits")
                            .setMessage("Save changes before leaving?")
                            .setPositiveButton(
                                    "Save",
                                    (d, w) -> {
                                        if (state.standalone) {
                                            exportDocument();
                                            return;
                                        }
                                        saving = true;
                                        work(
                                                "Saving edits",
                                                () -> {
                                                    stageDraft();
                                                    state.session.save();
                                                    return true;
                                                },
                                                v -> finish());
                                    })
                            .setNeutralButton("Discard", (d, w) -> finish())
                            .setNegativeButton("Cancel", null));
        } else finish();
    }

    private final class RecordAdapter extends RecyclerView.Adapter<RecordHolder> {
        public RecordHolder onCreateViewHolder(ViewGroup parent, int type) {
            LinearLayout row = WorldUi.column(WorldEditorActivity.this, 16);
            row.setMinimumHeight(dp(56));
            TextView title = WorldUi.text(WorldEditorActivity.this, "", 14, false);
            title.setSingleLine(true);
            title.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
            row.addView(title);
            return new RecordHolder(row, title);
        }

        public void onBindViewHolder(RecordHolder holder, int position) {
            byte[] key = recordKeys.get(position);
            String label = new String(key, StandardCharsets.UTF_8);
            holder.title.setText(
                    label.startsWith("structuretemplate_") ? label.substring(18) : label);
            holder.itemView.setOnClickListener(v -> recordMenu(key));
            holder.itemView.setOnLongClickListener(
                    v -> {
                        recordMenu(key);
                        return true;
                    });
        }

        @Override
        public int getItemCount() {
            return recordKeys.size();
        }
    }

    private static final class RecordHolder extends RecyclerView.ViewHolder {
        final TextView title;

        RecordHolder(View view, TextView title) {
            super(view);
            this.title = title;
        }
    }
}
